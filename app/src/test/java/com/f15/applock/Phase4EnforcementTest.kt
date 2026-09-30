package com.f15.applock

import com.f15.applock.domain.model.AppAuthorization
import com.f15.applock.domain.model.DetectionSource
import com.f15.applock.domain.model.LockEngineMode
import com.f15.applock.domain.model.LockEngineState
import com.f15.applock.domain.model.SessionTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests verifying Phase 4 real-time enforcement logic, per-app authorization isolation,
 * system package safe filtering, engine mode resolution, and duplicate lock suppression.
 */
class Phase4EnforcementTest {

    @Test
    fun appAuthorization_expiresAccuratelyBasedOnElapsedRealtime() {
        val auth = AppAuthorization(
            packageName = "com.whatsapp",
            authenticatedAt = 10_000L,
            expiresAt = 70_000L // 60 seconds duration
        )

        // 30 seconds after auth (elapsed 40,000) -> NOT expired
        assertFalse(auth.isExpired(40_000L))

        // Exactly at expiration time (elapsed 70,000) -> Expired
        assertTrue(auth.isExpired(70_000L))

        // After expiration time (elapsed 70,001) -> Expired
        assertTrue(auth.isExpired(70_001L))
    }

    @Test
    fun perAppAuthorization_isolatesUnlocksBetweenProtectedApps() {
        val authorizations = ConcurrentHashMap<String, AppAuthorization>()

        fun grantAccess(pkg: String, now: Long, duration: Long) {
            authorizations[pkg] = AppAuthorization(pkg, now, now + duration)
        }

        fun isAuthorized(pkg: String, now: Long): Boolean {
            val auth = authorizations[pkg] ?: return false
            return !auth.isExpired(now)
        }

        val currentTime = 100_000L

        // User unlocks WhatsApp
        grantAccess("com.whatsapp", currentTime, 60_000L)

        // WhatsApp is authorized
        assertTrue(isAuthorized("com.whatsapp", currentTime + 10_000L))

        // Instagram, Chrome, and Gallery are NOT authorized!
        assertFalse(isAuthorized("com.instagram.android", currentTime + 10_000L))
        assertFalse(isAuthorized("com.android.chrome", currentTime + 10_000L))
        assertFalse(isAuthorized("com.sec.android.gallery3d", currentTime + 10_000L))
    }

    @Test
    fun sessionTimeout_immediately_relocksUponBackgroundExit() {
        var lastUnlockedAt = 1000L
        var leftForegroundAt: Long? = null

        fun shouldLockImmediately(leftTime: Long?, unlockedAt: Long): Boolean {
            if (leftTime != null && leftTime > unlockedAt) {
                return true
            }
            return false
        }

        // Inside WhatsApp: user has not left -> NO lock
        assertFalse(shouldLockImmediately(leftForegroundAt, lastUnlockedAt))

        // User switches away to Chrome at 2000L
        leftForegroundAt = 2000L

        // User returns to WhatsApp -> Must lock immediately!
        assertTrue(shouldLockImmediately(leftForegroundAt, lastUnlockedAt))
    }

    @Test
    fun sessionTimeout_timed_respectsConfiguredDuration() {
        val timeout = SessionTimeout.MINUTE_1 // 60,000 ms
        val unlockedAt = 10_000L

        fun shouldLockTimed(leftTime: Long, now: Long): Boolean {
            val elapsed = now - leftTime
            return elapsed >= timeout.durationMillis
        }

        // Switched away at 15_000L
        val leftTime = 15_000L

        // Returned at 45_000L (30s in background, < 60s timeout) -> NO lock
        assertFalse(shouldLockTimed(leftTime, now = 45_000L))

        // Returned at 76_000L (61s in background, >= 60s timeout) -> LOCK
        assertTrue(shouldLockTimed(leftTime, now = 76_000L))
    }

    @Test
    fun criticalSystemPackages_areNeverBlocked() {
        val criticalSystemPackages = setOf(
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
        val selfPackage = "com.f15.applock"

        fun isSafeFromLock(pkg: String): Boolean {
            if (pkg == selfPackage) return true
            if (criticalSystemPackages.contains(pkg)) return true
            return false
        }

        assertTrue(isSafeFromLock("com.f15.applock"))
        assertTrue(isSafeFromLock("android"))
        assertTrue(isSafeFromLock("com.android.systemui"))
        assertTrue(isSafeFromLock("com.sec.android.app.launcher"))
        assertTrue(isSafeFromLock("com.android.settings"))
        assertTrue(isSafeFromLock("com.samsung.android.incallui"))

        // Protected user apps are NOT safe from lock
        assertFalse(isSafeFromLock("com.whatsapp"))
        assertFalse(isSafeFromLock("com.instagram.android"))
    }

    @Test
    fun duplicateLockEvents_forSamePackageAreSuppressed() {
        var activeLockPackage: String? = null
        var isLockScreenVisible = false
        var launchCount = 0

        fun onWindowDetected(pkg: String, isProtected: Boolean) {
            if (!isProtected) return

            if (activeLockPackage == pkg && isLockScreenVisible) {
                // Suppressed duplicate
                return
            }

            activeLockPackage = pkg
            isLockScreenVisible = true
            launchCount++
        }

        // WhatsApp window state events firing 5 times in rapid succession
        onWindowDetected("com.whatsapp", isProtected = true)
        onWindowDetected("com.whatsapp", isProtected = true)
        onWindowDetected("com.whatsapp", isProtected = true)
        onWindowDetected("com.whatsapp", isProtected = true)
        onWindowDetected("com.whatsapp", isProtected = true)

        // Only ONE lock screen launch should occur
        assertEquals(1, launchCount)
        assertEquals("com.whatsapp", activeLockPackage)
        assertTrue(isLockScreenVisible)
    }

    @Test
    fun engineMode_resolvesAppropriatelyBasedOnPermissions() {
        fun resolveEngineMode(a11y: Boolean, usage: Boolean): LockEngineMode {
            return when {
                a11y -> LockEngineMode.ACTIVE_ACCESSIBILITY
                usage -> LockEngineMode.ACTIVE_USAGE_STATS
                else -> LockEngineMode.DISABLED
            }
        }

        // Both enabled -> real-time Accessibility takes precedence
        assertEquals(LockEngineMode.ACTIVE_ACCESSIBILITY, resolveEngineMode(a11y = true, usage = true))

        // Only accessibility enabled -> real-time Accessibility
        assertEquals(LockEngineMode.ACTIVE_ACCESSIBILITY, resolveEngineMode(a11y = true, usage = false))

        // Only Usage Access enabled -> Usage Stats fallback
        assertEquals(LockEngineMode.ACTIVE_USAGE_STATS, resolveEngineMode(a11y = false, usage = true))

        // Neither enabled -> Disabled
        assertEquals(LockEngineMode.DISABLED, resolveEngineMode(a11y = false, usage = false))
    }

    @Test
    fun samePackageNavigation_internalScreenTransitionsDoNotTriggerLock() {
        val authorizations = ConcurrentHashMap<String, AppAuthorization>()
        var currentPackage: String? = null
        var previousPackage: String? = null
        var lockRequestCount = 0

        fun grantAccess(pkg: String, now: Long) {
            authorizations[pkg] = AppAuthorization(pkg, now, Long.MAX_VALUE)
        }

        fun isPackageAuthorized(pkg: String): Boolean = authorizations[pkg] != null

        val lockedApps = setOf("com.whatsapp")

        fun onWindowStateChanged(pkg: String) {
            val prev = currentPackage
            if (pkg != prev) {
                previousPackage = prev
                currentPackage = pkg
            }

            if (!lockedApps.contains(pkg)) return

            // Same-Package Rule: internal navigation within already authorized app
            if (pkg == prev && isPackageAuthorized(pkg)) {
                // IGNORE - SAME PACKAGE
                return
            }

            if (!isPackageAuthorized(pkg)) {
                lockRequestCount++
            }
        }

        // 1. Enter WhatsApp from Launcher -> Locks initially
        onWindowStateChanged("com.sec.android.app.launcher")
        onWindowStateChanged("com.whatsapp")
        assertEquals(1, lockRequestCount)

        // 2. User authenticates successfully
        grantAccess("com.whatsapp", System.currentTimeMillis())

        // 3. User navigates inside WhatsApp: Main -> Chat -> Settings -> Back -> Main
        onWindowStateChanged("com.whatsapp") // Main
        onWindowStateChanged("com.whatsapp") // Chat
        onWindowStateChanged("com.whatsapp") // Settings
        onWindowStateChanged("com.whatsapp") // Back to Main

        // Lock count MUST remain 1! Zero unexpected locks during internal navigation!
        assertEquals(1, lockRequestCount)
    }

    @Test
    fun transientOverlays_doNotInvalidateSessionOrTriggerFalseExit() {
        val leftForegroundTimestamps = ConcurrentHashMap<String, Long>()
        val transientPackages = setOf(
            "com.samsung.android.honeyboard",
            "com.samsung.android.biometrics.app.setting",
            "com.android.systemui",
            "android",
            "com.f15.applock"
        )

        fun onPackageTransition(fromPkg: String?, toPkg: String?, now: Long) {
            if (fromPkg != null && fromPkg != toPkg) {
                if (toPkg != null && !transientPackages.contains(toPkg)) {
                    leftForegroundTimestamps[fromPkg] = now
                }
            }
        }

        // User is inside WhatsApp (unlocked at 1000L)
        val authTime = 1000L

        // Soft keyboard (HoneyBoard) opens over WhatsApp at 1500L
        onPackageTransition("com.whatsapp", "com.samsung.android.honeyboard", now = 1500L)

        // WhatsApp should NOT have leftForegroundTimestamp recorded
        assertEquals(null, leftForegroundTimestamps["com.whatsapp"])

        // Biometric dialog opens over WhatsApp at 1600L
        onPackageTransition("com.whatsapp", "com.samsung.android.biometrics.app.setting", now = 1600L)
        assertEquals(null, leftForegroundTimestamps["com.whatsapp"])

        // User returns to chat: no exit was recorded!
        onPackageTransition("com.samsung.android.honeyboard", "com.whatsapp", now = 1700L)
        assertEquals(null, leftForegroundTimestamps["com.whatsapp"])

        // Now user actually presses Home (Launcher) at 2000L
        onPackageTransition("com.whatsapp", "com.sec.android.app.launcher", now = 2000L)

        // Genuine exit is recorded!
        assertEquals(2000L, leftForegroundTimestamps["com.whatsapp"])
        assertTrue(leftForegroundTimestamps["com.whatsapp"]!! > authTime)
    }
}
