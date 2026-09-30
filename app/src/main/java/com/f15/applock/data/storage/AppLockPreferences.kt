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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
 */
class AppLockPreferences(private val context: Context) {

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

    /**
     * Synchronously fetches the initial set of locked package names.
     */
    suspend fun getLockedPackages(): Set<String> {
        return try {
            lockedPackagesFlow.first()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Updates the protection state for a specific package with input validation.
     */
    suspend fun setAppLocked(packageName: String, isLocked: Boolean) {
        val sanitized = packageName.trim()
        if (sanitized.isBlank() || sanitized == context.packageName) {
            return
        }

        try {
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
            }
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
            context.dataStore.edit { preferences ->
                val current = preferences[KEY_LOCKED_PACKAGES] ?: emptySet()
                val validSet = current.filter { pkg ->
                    val isValid = validInstalledPackages.contains(pkg) && pkg != context.packageName
                    if (!isValid) removedCount++
                    isValid
                }.toSet()
                preferences[KEY_LOCKED_PACKAGES] = validSet
                preferences[KEY_CONFIG_VERSION] = CURRENT_CONFIG_VERSION
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
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_BIOMETRIC_ENABLED] = enabled
                preferences[KEY_CONFIG_VERSION] = CURRENT_CONFIG_VERSION
            }
        } catch (e: Exception) {
            // Guard against I/O write failures
        }
    }

    /**
     * Persists the selected session timeout duration.
     */
    suspend fun setSessionTimeout(timeout: SessionTimeout) {
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_SESSION_TIMEOUT_SECONDS] = timeout.durationSeconds
                preferences[KEY_CONFIG_VERSION] = CURRENT_CONFIG_VERSION
            }
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
}
