package com.f15.applock.security

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.f15.applock.data.repository.AppTargetCache
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.domain.model.AppAuthorization
import com.f15.applock.domain.model.SessionTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Evaluates whether an incoming foreground application requires authentication.
 *
 * Responsibilities:
 * - Validates against the user-selected protected package set from DataStore.
 * - Suppresses locking for the Android Launcher, system UI, critical system dialogs, and App Lock itself.
 * - Enforces per-app authorization grants and background timeout policies (Phase 4 & 5.3).
 * - Dynamically identifies default and installed home / launcher packages.
 * - Ensures fail-closed authorization semantics without arbitrary sleep or debounce delays.
 */
class LockDecisionManager(
    private val context: Context,
    private val preferences: AppLockPreferences,
    private val sessionManager: SessionManager
) {

    companion object {
        private const val TAG = "LockDecisionManager"

        /**
         * System overlays, contextual tools, media companions, and assistants that do NOT
         * represent navigating away from the active application.
         */
        val TRANSIENT_OVERLAY_PACKAGES: Set<String> = setOf(
            "com.samsung.android.biometrics.app.setting",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.android.systemui",
            "android",
            // Google Circle to Search, Google Assistant, and System Intelligence
            "com.google.android.googlequicksearchbox",
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.apps.search.assistant",
            "com.google.android.apps.googleassistant",
            "com.google.android.voiceinteraction",
            // Samsung Gallery integrated media viewers & editors
            "com.samsung.android.video",
            "com.sec.android.app.videoplayer",
            "com.samsung.android.app.videoplayer",
            "com.sec.android.mimage.photoretouching",
            "com.sec.android.app.vepreload",
            "com.sec.android.app.ve",
            "com.samsung.android.videoeditor",
            "com.samsung.android.app.moviecreator",
            // Samsung companion overlays & intelligence
            "com.samsung.android.visionintelligence",
            "com.samsung.android.visualsearch",
            "com.samsung.android.bixby.agent",
            "com.samsung.android.rubin.app",
            "com.samsung.android.smartcapture",
            "com.samsung.android.app.cocktailbarservice"
        )
    }

    // Map storing per-app authorization sessions
    private val authorizations = ConcurrentHashMap<String, AppAuthorization>()

    // Map storing when a package left the foreground (SystemClock.elapsedRealtime)
    private val leftForegroundTimestamps = ConcurrentHashMap<String, Long>()

    private val myPackageName: String = context.packageName

    @Volatile
    private var currentTimeout: SessionTimeout = SessionTimeout.MINUTE_1

    @Volatile
    private var cachedLockedPackages: Set<String> = emptySet()

    @Volatile
    private var cachedLauncherPackage: String? = null
    private val allLauncherPackages = ConcurrentHashMap.newKeySet<String>()
    private val cachedInputMethodPackages = ConcurrentHashMap.newKeySet<String>()

    init {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            preferences.sessionTimeoutFlow.collect { timeout ->
                currentTimeout = timeout
            }
        }
        scope.launch {
            preferences.lockedPackagesFlow.collect { packages ->
                cachedLockedPackages = packages
                AppTargetCache.prewarm(context, packages)
            }
        }
        refreshLauncherPackages()
        refreshInputMethods()
    }

    // Critical system packages that must never be blocked to prevent bricking or OS deadlocks
    private val criticalSystemPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.sec.android.app.launcher",
        "com.samsung.android.app.telephonyui",
        "com.samsung.android.incallui",
        "com.google.android.dialer",
        "com.android.dialer",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.gms",
        "com.google.android.packageinstaller"
    )

    /**
     * Resolves and caches all launcher packages installed on the device dynamically.
     */
    fun refreshLauncherPackages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = context.packageManager.resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            cachedLauncherPackage = resolveInfo?.activityInfo?.packageName

            val list = context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            allLauncherPackages.clear()
            for (info in list) {
                val pkg = info.activityInfo?.packageName
                if (!pkg.isNullOrBlank()) {
                    allLauncherPackages.add(pkg)
                }
            }
            cachedLauncherPackage?.let { allLauncherPackages.add(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve launcher packages", e)
        }
    }

    /**
     * Resolves the current default Android home / launcher package dynamically with caching.
     */
    fun getLauncherPackage(): String? {
        var launcher = cachedLauncherPackage
        if (launcher == null) {
            refreshLauncherPackages()
            launcher = cachedLauncherPackage
        }
        return launcher
    }

    /**
     * Checks if a package is a known or resolved launcher / Home application.
     */
    fun isLauncherPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val defaultLauncher = getLauncherPackage()
        if (defaultLauncher != null && packageName == defaultLauncher) return true
        if (allLauncherPackages.contains(packageName)) return true
        if (packageName == "com.sec.android.app.launcher" ||
            packageName == "com.google.android.apps.nexuslauncher" ||
            packageName == "com.android.launcher3"
        ) return true
        return false
    }

    /**
     * Checks if a package is currently protected in user preferences (O(1) in-memory check).
     */
    fun isPackageProtected(packageName: String): Boolean {
        return cachedLockedPackages.contains(packageName)
    }

    /**
     * Checks if a package is a system navigation component or self that must never be locked.
     */
    fun isIgnoredPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (packageName == myPackageName) return true
        if (criticalSystemPackages.contains(packageName)) return true
        if (isLauncherPackage(packageName)) return true
        return false
    }

    /**
     * Checks if a package is a transient system overlay, input method (soft keyboard),
     * biometric prompt, assistant/search overlay (e.g. Google Circle to Search),
     * media companion (e.g. Samsung Video inside Gallery), or self that does NOT
     * represent navigating away from the active application.
     */
    fun isTransientOverlay(packageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (packageName == myPackageName) return true
        // If the package is explicitly protected by the user, never treat as transient overlay
        if (isPackageProtected(packageName)) return false
        if (TRANSIENT_OVERLAY_PACKAGES.contains(packageName)) return true
        if (isInputMethod(packageName)) return true
        return false
    }

    fun refreshInputMethods() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            val list = imm?.enabledInputMethodList
            if (list != null) {
                cachedInputMethodPackages.clear()
                for (imi in list) {
                    val pkg = imi.packageName
                    if (!pkg.isNullOrBlank()) {
                        cachedInputMethodPackages.add(pkg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve enabled input methods", e)
        }
    }

    private fun isInputMethod(packageName: String): Boolean {
        if (cachedInputMethodPackages.contains(packageName)) return true
        return packageName.contains("honeyboard") ||
                packageName.contains("inputmethod") ||
                packageName.contains("gboard") ||
                packageName.contains("swiftkey")
    }

    /**
     * Called when a protected package exits foreground (to Home, another app, or screen off).
     * Synchronously invalidates authorizations if the configured policy is IMMEDIATELY.
     */
    fun onPackageExited(packageName: String) {
        val now = SystemClock.elapsedRealtime()
        leftForegroundTimestamps[packageName] = now
        if (currentTimeout == SessionTimeout.IMMEDIATELY) {
            authorizations.remove(packageName)
            Log.d(TAG, "[AppLock] AUTHORIZATION → INVALID (session invalidated immediately on exit for $packageName)")
        } else {
            Log.d(TAG, "[AppLock] Package $packageName left foreground at $now (timeout: ${currentTimeout.durationSeconds}s)")
        }
    }

    /**
     * Called when a transition between foreground applications occurs.
     */
    fun onPackageTransition(fromPackage: String?, toPackage: String?) {
        if (fromPackage != null && fromPackage != toPackage) {
            if (toPackage != null && !isTransientOverlay(toPackage)) {
                onPackageExited(fromPackage)
            }
        }
    }

    /**
     * Called when the device screen turns off or locks.
     */
    fun onScreenOff(currentActivePackage: String?) {
        val now = SystemClock.elapsedRealtime()
        if (currentActivePackage != null) {
            leftForegroundTimestamps[currentActivePackage] = now
        }
        if (currentTimeout == SessionTimeout.IMMEDIATELY) {
            authorizations.clear()
        }
        sessionManager.onAppBackgrounded()
    }

    /**
     * Evaluates whether [packageName] requires authentication (fail-closed O(1) in-memory check).
     */
    fun shouldLock(packageName: String): Boolean {
        if (isIgnoredPackage(packageName)) {
            return false
        }

        // 1. Verify if the package is in the user's protected list (in-memory)
        if (!cachedLockedPackages.contains(packageName)) {
            return false
        }

        // 2. Check per-app authorization for this specific package
        val auth = authorizations[packageName] ?: return true

        val timeout = currentTimeout
        val leftTime = leftForegroundTimestamps[packageName]

        if (timeout == SessionTimeout.IMMEDIATELY) {
            // Under IMMEDIATE policy, any background exit invalidates authorization
            if (leftTime != null && leftTime > auth.authenticatedAt) {
                authorizations.remove(packageName)
                return true
            }
            return false
        } else {
            // Check if background duration exceeded timeout
            if (leftTime != null && leftTime > auth.authenticatedAt) {
                val backgroundElapsed = SystemClock.elapsedRealtime() - leftTime
                if (backgroundElapsed >= timeout.durationMillis) {
                    authorizations.remove(packageName)
                    return true
                }
            }
            return false
        }
    }

    /**
     * Grants temporary per-app authorization to [packageName] following successful authentication.
     */
    fun grantAccess(packageName: String) {
        val now = SystemClock.elapsedRealtime()
        val timeout = currentTimeout
        val expiresAt = if (timeout == SessionTimeout.IMMEDIATELY) {
            Long.MAX_VALUE
        } else {
            now + timeout.durationMillis
        }

        authorizations[packageName] = AppAuthorization(
            packageName = packageName,
            authenticatedAt = now,
            expiresAt = expiresAt
        )
        leftForegroundTimestamps.remove(packageName)
    }

    /**
     * Revokes access for a package.
     */
    fun revokeAccess(packageName: String) {
        authorizations.remove(packageName)
        leftForegroundTimestamps.remove(packageName)
    }

    /**
     * Clears all per-app authorizations.
     */
    fun clearAllAccess() {
        authorizations.clear()
        leftForegroundTimestamps.clear()
        sessionManager.lockSession()
    }

    /**
     * Inspects whether a package currently holds valid authorization.
     */
    fun isPackageAuthorized(packageName: String): Boolean {
        return getAuthorizationStatus(packageName) == "VALID"
    }

    /**
     * Returns a human-readable authorization status for diagnostics and logs.
     */
    fun getAuthorizationStatus(packageName: String?): String {
        if (packageName == null) return "NONE"
        val auth = authorizations[packageName] ?: return "INVALID"
        val leftTime = leftForegroundTimestamps[packageName]
        val timeout = currentTimeout

        if (timeout == SessionTimeout.IMMEDIATELY) {
            if (leftTime != null && leftTime > auth.authenticatedAt) return "EXPIRED"
            return "VALID"
        } else {
            if (leftTime != null && leftTime > auth.authenticatedAt) {
                val elapsed = SystemClock.elapsedRealtime() - leftTime
                return if (elapsed >= timeout.durationMillis) "EXPIRED" else "VALID"
            }
            return "VALID"
        }
    }
}
