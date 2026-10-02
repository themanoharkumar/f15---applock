package com.f15.applock.data.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.f15.applock.domain.model.SessionTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_lock_preferences")

/**
 * DataStore-backed persistence layer for storing and retrieving non-sensitive preferences
 * such as locked package identifiers, biometric toggle state, session timeout options,
 * and configuration versioning.
 *
 * Hardened for Phase 5:
 * - Package validation & sanitization (prevents blank/corrupted entries).
 * - Self-locking prevention at the persistence boundary.
 * - Stale package cleanup mechanism when apps are uninstalled.
 * - Configuration schema versioning (config_version = 1).
 * - In-memory synchronous caching for zero-delay security path lookups.
 */
class AppLockPreferences(private val context: Context) {

    private val integrityManager by lazy {
        com.f15.applock.security.SecurityIntegrityManager(context)
    }

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var cachedLockedPackages: Set<String> = emptySet()
        private set

    @Volatile
    var cachedIsBiometricEnabled: Boolean = true
        private set

    @Volatile
    var cachedSessionTimeout: SessionTimeout = SessionTimeout.MINUTE_1
        private set

    companion object {
        const val CURRENT_CONFIG_VERSION = 1

        val KEY_LOCKED_PACKAGES = stringSetPreferencesKey("locked_packages")
        val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val KEY_SESSION_TIMEOUT_SECONDS = longPreferencesKey("session_timeout_seconds")
        val KEY_CONFIG_VERSION = intPreferencesKey("config_version")
    }

    /**
     * Flow emitting the current set of locked package names with sanitization.
     */
    val lockedPackagesFlow: Flow<Set<String>> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val set = preferences[KEY_LOCKED_PACKAGES] ?: emptySet()
            // Sanitize: filter out blanks, self-package, and invalid characters
            set.filter { pkg -> pkg.isNotBlank() && pkg != context.packageName }.toSet()
        }

    /**
     * Flow emitting whether biometric authentication is enabled by the user (defaults to true).
     */
    val isBiometricEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_BIOMETRIC_ENABLED] ?: true
        }

    /**
     * Flow emitting the configured session timeout (defaults to 1 minute).
     */
    val sessionTimeoutFlow: Flow<SessionTimeout> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val seconds = preferences[KEY_SESSION_TIMEOUT_SECONDS] ?: SessionTimeout.MINUTE_1.durationSeconds
            SessionTimeout.fromDuration(seconds)
        }

    /**
     * Flow emitting current configuration schema version.
     */
    val configVersionFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[KEY_CONFIG_VERSION] ?: CURRENT_CONFIG_VERSION
        }

    init {
        cacheScope.launch {
            lockedPackagesFlow.collect {
                cachedLockedPackages = it
            }
        }
        cacheScope.launch {
            isBiometricEnabledFlow.collect {
                cachedIsBiometricEnabled = it
            }
        }
        cacheScope.launch {
            sessionTimeoutFlow.collect {
                cachedSessionTimeout = it
            }
        }
    }

    /**
     * Synchronously fetches the initial set of locked package names.
     */
    suspend fun getLockedPackages(): Set<String> {
        if (cachedLockedPackages.isNotEmpty()) return cachedLockedPackages
        return try {
            lockedPackagesFlow.first().also { cachedLockedPackages = it }
        } catch (e: Exception) {
            cachedLockedPackages
        }
    }

    /**
     * Updates the protection state for a specific package with input validation and cryptographic integrity signing.
     */
    suspend fun setAppLocked(packageName: String, isLocked: Boolean) {
        val sanitized = packageName.trim()
        if (sanitized.isBlank() || sanitized == context.packageName) {
            return
        }

        // Fast-path in-memory update
        val updatedImmediate = cachedLockedPackages.toMutableSet().apply {
            if (isLocked) add(sanitized) else remove(sanitized)
        }.toSet()
        cachedLockedPackages = updatedImmediate

        try {
            var updatedPackages: Set<String> = emptySet()
            var bio = true
            var timeout = SessionTimeout.MINUTE_1.durationSeconds
            var ver = CURRENT_CONFIG_VERSION

            context.dataStore.edit { preferences ->
                val current = (preferences[KEY_LOCKED_PACKAGES] ?: emptySet())
                    .filter { it.isNotBlank() && it != context.packageName }
                    .toMutableSet()

                if (isLocked) {
                    current.add(sanitized)
                } else {
                    current.remove(sanitized)
                }
                preferences[KEY_LOCKED_PACKAGES] = current
                preferences[KEY_CONFIG_VERSION] = CURRENT_CONFIG_VERSION

                updatedPackages = current
                bio = preferences[KEY_BIOMETRIC_ENABLED] ?: true
                timeout = preferences[KEY_SESSION_TIMEOUT_SECONDS] ?: SessionTimeout.MINUTE_1.durationSeconds
                ver = preferences[KEY_CONFIG_VERSION] ?: CURRENT_CONFIG_VERSION
            }

            cachedLockedPackages = updatedPackages

            // Cryptographically sign configuration
            integrityManager.signConfiguration(updatedPackages, bio, timeout, ver)

            // Audit logging
            val eventType = if (isLocked) {
                com.f15.applock.security.SecurityEventType.PROTECTED_APP_ADDED
            } else {
                com.f15.applock.security.SecurityEventType.PROTECTED_APP_REMOVED
            }
            com.f15.applock.security.SecurityEventLogger.log(
                type = eventType,
                details = if (isLocked) "Added '$sanitized' to protected applications list" else "Removed '$sanitized' from protected applications list",
                component = "ProtectedPackages",
                packageName = sanitized
            )
        } catch (e: Exception) {
            // Guard against I/O write failures
        }
    }

    /**
     * Cleans up stale package entries for apps that are no longer installed on the device.
     *
     * @param validInstalledPackages Set of package names currently installed on the device.
     * @return Count of stale packages that were purged.
     */
    suspend fun cleanupStalePackages(validInstalledPackages: Set<String>): Int {
        var removedCount = 0
        try {
            var updatedPackages: Set<String> = emptySet()
            var bio = true
            var timeout = SessionTimeout.MINUTE_1.durationSeconds
            var ver = CURRENT_CONFIG_VERSION

            context.dataStore.edit { preferences ->
                val current = preferences[KEY_LOCKED_PACKAGES] ?: emptySet()
                val validSet = current.filter { pkg ->
                    val isValid = validInstalledPackages.contains(pkg) && pkg != context.packageName
                    if (!isValid) removedCount++
                    isValid
                }.toSet()
                preferences[KEY_LOCKED_PACKAGES] = validSet
                preferences[KEY_CONFIG_VERSION] = CURRENT_CONFIG_VERSION

                updatedPackages = validSet
                bio = preferences[KEY_BIOMETRIC_ENABLED] ?: true
                timeout = preferences[KEY_SESSION_TIMEOUT_SECONDS] ?: SessionTimeout.MINUTE_1.durationSeconds
                ver = preferences[KEY_CONFIG_VERSION] ?: CURRENT_CONFIG_VERSION
            }

            cachedLockedPackages = updatedPackages

            if (removedCount > 0) {
                integrityManager.signConfiguration(updatedPackages, bio, timeout, ver)
                com.f15.applock.security.SecurityEventLogger.log(
                    type = com.f15.applock.security.SecurityEventType.CLEANUP_STALE_PACKAGES,
                    details = "Purged $removedCount uninstalled packages from protected list",
                    component = "ProtectedPackages"
                )
            }
        } catch (e: Exception) {
            // Guard against I/O write failures
        }
        return removedCount
    }

    /**
     * Toggles biometric unlock preference.
     */
    suspend fun setBiometricEnabled(enabled: Boolean) {
        cachedIsBiometricEnabled = enabled
        try {
            var pkgs: Set<String> = emptySet()
            var timeout = SessionTimeout.MINUTE_1.durationSeconds
            var ver = CURRENT_CONFIG_VERSION

            context.dataStore.edit { preferences ->
                preferences[KEY_BIOMETRIC_ENABLED] = enabled
                preferences[KEY_CONFIG_VERSION] = CURRENT_CONFIG_VERSION

                pkgs = (preferences[KEY_LOCKED_PACKAGES] ?: emptySet())
                    .filter { it.isNotBlank() && it != context.packageName }.toSet()
                timeout = preferences[KEY_SESSION_TIMEOUT_SECONDS] ?: SessionTimeout.MINUTE_1.durationSeconds
                ver = preferences[KEY_CONFIG_VERSION] ?: CURRENT_CONFIG_VERSION
            }

            integrityManager.signConfiguration(pkgs, enabled, timeout, ver)
        } catch (e: Exception) {
            // Guard against I/O write failures
        }
    }

    /**
     * Persists the selected session timeout duration.
     */
    suspend fun setSessionTimeout(timeout: SessionTimeout) {
        cachedSessionTimeout = timeout
        try {
            var pkgs: Set<String> = emptySet()
            var bio = true
            var ver = CURRENT_CONFIG_VERSION

            context.dataStore.edit { preferences ->
                preferences[KEY_SESSION_TIMEOUT_SECONDS] = timeout.durationSeconds
                preferences[KEY_CONFIG_VERSION] = CURRENT_CONFIG_VERSION

                pkgs = (preferences[KEY_LOCKED_PACKAGES] ?: emptySet())
                    .filter { it.isNotBlank() && it != context.packageName }.toSet()
                bio = preferences[KEY_BIOMETRIC_ENABLED] ?: true
                ver = preferences[KEY_CONFIG_VERSION] ?: CURRENT_CONFIG_VERSION
            }

            integrityManager.signConfiguration(pkgs, bio, timeout.durationSeconds, ver)
        } catch (e: Exception) {
            // Guard against I/O write failures
        }
    }



    /**
     * Fetches the current session timeout.
     */
    suspend fun getSessionTimeout(): SessionTimeout {
        return try {
            sessionTimeoutFlow.first()
        } catch (e: Exception) {
            SessionTimeout.MINUTE_1
        }
    }

    /**
     * Fetches the configuration schema version.
     */
    suspend fun getConfigVersion(): Int {
        return try {
            configVersionFlow.first()
        } catch (e: Exception) {
            CURRENT_CONFIG_VERSION
        }
    }

    /**
     * Authoritatively verifies configuration integrity and package list structure.
     */
    suspend fun verifyIntegrity(): com.f15.applock.security.IntegrityCheckResult {
        val pkgs = getLockedPackages()
        val bio = isBiometricEnabledFlow.first()
        val timeout = sessionTimeoutFlow.first().durationSeconds
        val ver = getConfigVersion()
        return integrityManager.verifyConfiguration(pkgs, bio, timeout, ver)
    }

    /**
     * Resets and re-signs configuration in authenticated Administrator Recovery Mode.
     */
    suspend fun recoverAndSignConfiguration(): Boolean {
        val pkgs = getLockedPackages()
        val bio = isBiometricEnabledFlow.first()
        val timeout = sessionTimeoutFlow.first().durationSeconds
        val ver = getConfigVersion()
        return integrityManager.recoverAndSignConfiguration(pkgs, bio, timeout, ver)
    }
}
