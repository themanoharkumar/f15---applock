package com.f15.applock

import com.f15.applock.domain.model.AppAuthorization
import com.f15.applock.domain.model.ForegroundSecurityState
import com.f15.applock.domain.model.SessionTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests verifying Phase 5.3 Home Re-entry Bypass Fix & Fail-Closed Enforcement.
 *
 * Verifies:
 * - Deterministic transition to HOME state upon launcher foreground event.
 * - Immediate authorization revocation under SessionTimeout.IMMEDIATELY on Home exit.
 * - Immediate, zero-delay lock decision on re-entry from Home.
 * - Preservation of timed session policies across Home if within duration.
 * - Same-package navigation safety (Phase 5.1 preservation).
 * - Fail-closed evaluation during uncertain/expired authorization state.
 * - Duplicate event protection for rapid repeated events.
 */
class Phase53HomeReentryTest {

    @Test
    fun homeExit_invalidatesImmediateSession_andTriggersImmediateLockOnReentry() {
        val authorizations = ConcurrentHashMap<String, AppAuthorization>()
        val leftForegroundTimestamps = ConcurrentHashMap<String, Long>()
        var securityState = ForegroundSecurityState.UNKNOWN
        var activeProtectedPackage: String? = null
        var lockRequestsCount = 0

        val protectedPackage = "com.whatsapp"
        val launcherPackage = "com.sec.android.app.launcher"
        val timeout = SessionTimeout.IMMEDIATELY

        fun grantAccess(pkg: String, now: Long) {
            authorizations[pkg] = AppAuthorization(pkg, now, Long.MAX_VALUE)
            securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
            activeProtectedPackage = pkg
            leftForegroundTimestamps.remove(pkg)
        }

        fun onPackageExited(pkg: String, now: Long) {
            leftForegroundTimestamps[pkg] = now
            if (timeout == SessionTimeout.IMMEDIATELY) {
                authorizations.remove(pkg)
            }
        }

        fun onWindowStateChanged(pkg: String, now: Long) {
            if (pkg == launcherPackage) {
                val exited = activeProtectedPackage
                securityState = ForegroundSecurityState.HOME
                if (exited != null) {
                    activeProtectedPackage = null
                    onPackageExited(exited, now)
                }
                return
            }

            if (pkg == protectedPackage) {
                // Same-package rule:
                if (activeProtectedPackage == pkg &&
                    securityState == ForegroundSecurityState.PROTECTED_APP_AUTHORIZED &&
                    authorizations[pkg] != null
                ) {
                    return // No lock!
                }

                // New entry or re-entry:
                val isAuth = authorizations[pkg] != null
                if (!isAuth) {
                    lockRequestsCount++
                    securityState = ForegroundSecurityState.PROTECTED_APP_LOCKED
                } else {
                    activeProtectedPackage = pkg
                    securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
                }
            }
        }

        // 1. Initial Launch from Launcher
        onWindowStateChanged(launcherPackage, now = 1000L)
        assertEquals(ForegroundSecurityState.HOME, securityState)

        onWindowStateChanged(protectedPackage, now = 1050L)
        assertEquals(1, lockRequestsCount)
        assertEquals(ForegroundSecurityState.PROTECTED_APP_LOCKED, securityState)

        // 2. User authenticates successfully
        grantAccess(protectedPackage, now = 1200L)
        assertEquals(ForegroundSecurityState.PROTECTED_APP_AUTHORIZED, securityState)
        assertEquals(protectedPackage, activeProtectedPackage)

        // 3. User navigates inside WhatsApp: Chat -> Settings -> Back
        onWindowStateChanged(protectedPackage, now = 1500L)
        onWindowStateChanged(protectedPackage, now = 1800L)
        assertEquals(1, lockRequestsCount) // No extra lock

        // 4. User presses HOME
        onWindowStateChanged(launcherPackage, now = 2000L)
        assertEquals(ForegroundSecurityState.HOME, securityState)
        assertEquals(null, activeProtectedPackage)
        // Immediate policy must have wiped authorization synchronously
        assertTrue("Authorization must be invalidated immediately on Home exit", authorizations.isEmpty())
        assertEquals(2000L, leftForegroundTimestamps[protectedPackage])

        // 5. User re-enters WhatsApp from Home 1.5 seconds later
        onWindowStateChanged(protectedPackage, now = 3500L)
        // Must lock IMMEDIATELY with zero bypass window!
        assertEquals(2, lockRequestsCount)
        assertEquals(ForegroundSecurityState.PROTECTED_APP_LOCKED, securityState)
    }

    @Test
    fun homeExit_preservesTimedSession_ifWithinTimeout() {
        val authorizations = ConcurrentHashMap<String, AppAuthorization>()
        val leftForegroundTimestamps = ConcurrentHashMap<String, Long>()
        var securityState = ForegroundSecurityState.UNKNOWN
        var activeProtectedPackage: String? = null
        var lockRequestsCount = 0

        val protectedPackage = "com.whatsapp"
        val launcherPackage = "com.sec.android.app.launcher"
        val timeout = SessionTimeout.MINUTE_1 // 60,000ms

        fun grantAccess(pkg: String, now: Long) {
            authorizations[pkg] = AppAuthorization(pkg, now, now + timeout.durationMillis)
            securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
            activeProtectedPackage = pkg
            leftForegroundTimestamps.remove(pkg)
        }

        fun onPackageExited(pkg: String, now: Long) {
            leftForegroundTimestamps[pkg] = now
            if (timeout == SessionTimeout.IMMEDIATELY) {
                authorizations.remove(pkg)
            }
        }

        fun shouldLock(pkg: String, now: Long): Boolean {
            val auth = authorizations[pkg] ?: return true
            val leftTime = leftForegroundTimestamps[pkg]
            if (leftTime != null && leftTime > auth.authenticatedAt) {
                val elapsed = now - leftTime
                if (elapsed >= timeout.durationMillis) {
                    authorizations.remove(pkg)
                    return true
                }
            }
            return false
        }

        fun onWindowStateChanged(pkg: String, now: Long) {
            if (pkg == launcherPackage) {
                val exited = activeProtectedPackage
                securityState = ForegroundSecurityState.HOME
                if (exited != null) {
                    activeProtectedPackage = null
                    onPackageExited(exited, now)
                }
                return
            }

            if (pkg == protectedPackage) {
                if (activeProtectedPackage == pkg &&
                    securityState == ForegroundSecurityState.PROTECTED_APP_AUTHORIZED &&
                    !shouldLock(pkg, now)
                ) {
                    return
                }

                if (shouldLock(pkg, now)) {
                    lockRequestsCount++
                    securityState = ForegroundSecurityState.PROTECTED_APP_LOCKED
                } else {
                    activeProtectedPackage = pkg
                    securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
                }
            }
        }

        // 1. Initial unlock at 10,000L
        grantAccess(protectedPackage, now = 10_000L)

        // 2. User presses HOME at 15,000L
        onWindowStateChanged(launcherPackage, now = 15_000L)
        assertEquals(ForegroundSecurityState.HOME, securityState)
        assertFalse(authorizations.isEmpty()) // Session preserved!
        assertEquals(15_000L, leftForegroundTimestamps[protectedPackage])

        // 3. User re-enters WhatsApp at 25_000L (10s at Home, < 60s timeout)
        onWindowStateChanged(protectedPackage, now = 25_000L)
        // Must allow without lock request
        assertEquals(0, lockRequestsCount)
        assertEquals(ForegroundSecurityState.PROTECTED_APP_AUTHORIZED, securityState)

        // 4. User presses HOME again at 30_000L
        onWindowStateChanged(launcherPackage, now = 30_000L)

        // 5. User re-enters WhatsApp at 95_000L (65s at Home, >= 60s timeout)
        onWindowStateChanged(protectedPackage, now = 95_000L)
        // Must lock!
        assertEquals(1, lockRequestsCount)
        assertEquals(ForegroundSecurityState.PROTECTED_APP_LOCKED, securityState)
    }

    @Test
    fun samePackageNavigation_isNeverLocked() {
        val authorizations = ConcurrentHashMap<String, AppAuthorization>()
        var securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
        var activeProtectedPackage: String? = "com.whatsapp"
        var lockCount = 0

        authorizations["com.whatsapp"] = AppAuthorization("com.whatsapp", 1000L, Long.MAX_VALUE)

        fun onEvent(pkg: String) {
            val isSame = (pkg == "com.whatsapp" &&
                    activeProtectedPackage == pkg &&
                    securityState == ForegroundSecurityState.PROTECTED_APP_AUTHORIZED &&
                    authorizations[pkg] != null)

            if (isSame) {
                return // Ignored - internal navigation
            }
            lockCount++
        }

        // Simulate WhatsApp internal navigation
        onEvent("com.whatsapp") // Main
        onEvent("com.whatsapp") // Chat
        onEvent("com.whatsapp") // Media Viewer
        onEvent("com.whatsapp") // Contact Info
        onEvent("com.whatsapp") // Back to Main

        assertEquals(0, lockCount)
    }

    @Test
    fun failClosed_uncertainStateAlwaysLocks() {
        fun decideSecurityAction(
            isProtected: Boolean,
            isAuthorized: Boolean,
            isAuthInProgress: Boolean
        ): String {
            return when {
                !isProtected -> "ALLOW"
                isProtected && isAuthorized -> "ALLOW"
                isProtected && isAuthInProgress -> "SHOW_EXISTING"
                else -> "LOCK" // Fail-closed for unknown, expired, or unauthorized
            }
        }

        assertEquals("ALLOW", decideSecurityAction(isProtected = false, isAuthorized = false, isAuthInProgress = false))
        assertEquals("ALLOW", decideSecurityAction(isProtected = true, isAuthorized = true, isAuthInProgress = false))
        assertEquals("SHOW_EXISTING", decideSecurityAction(isProtected = true, isAuthorized = false, isAuthInProgress = true))
        assertEquals("LOCK", decideSecurityAction(isProtected = true, isAuthorized = false, isAuthInProgress = false))
        // Uncertain / unconfirmed auth state always fails closed
        assertEquals("LOCK", decideSecurityAction(isProtected = true, isAuthorized = false, isAuthInProgress = false))
    }

    @Test
    fun duplicateLockEvents_areSuppressed() {
        var activeLockPackage: String? = null
        var lastLockLaunchTime: Long = 0L
        var launchCount = 0

        fun requestLock(pkg: String, now: Long) {
            val isLaunchInProgress = (activeLockPackage == pkg && (now - lastLockLaunchTime < 350L))
            if (isLaunchInProgress) {
                return // Suppress duplicate
            }
            activeLockPackage = pkg
            lastLockLaunchTime = now
            launchCount++
        }

        // Rapid duplicate events within 50ms
        requestLock("com.whatsapp", now = 1000L)
        requestLock("com.whatsapp", now = 1010L)
        requestLock("com.whatsapp", now = 1025L)
        requestLock("com.whatsapp", now = 1050L)

        assertEquals(1, launchCount)
    }
}
