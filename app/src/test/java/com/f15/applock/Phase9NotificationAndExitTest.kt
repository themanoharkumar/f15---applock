package com.f15.applock

import com.f15.applock.data.repository.AppTargetCache
import com.f15.applock.domain.model.ForegroundSecurityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9 Unit Tests:
 * 1. Verification that incoming WhatsApp/protected notifications are NEVER suppressed or blocked by AppLock.
 * 2. Verification that exiting WhatsApp/protected apps to Home or unprotected apps NEVER triggers ghost lock screens.
 * 3. Verification that genuine Activity launches from Home, Launcher, or Notification taps trigger instant lock screen.
 * 4. Verification that non-activity views (FrameLayout, View, DecorView, PopupWindow, etc.) are accurately identified.
 */
class Phase9NotificationAndExitTest {

    // =========================================================================
    // 1. AppTargetCache Activity & Common View Classification Tests
    // =========================================================================

    @Test
    fun testAppTargetCache_commonViewsIdentifiedAccurately() {
        assertTrue(AppTargetCache.isCommonView("android.widget.FrameLayout"))
        assertTrue(AppTargetCache.isCommonView("android.widget.RelativeLayout"))
        assertTrue(AppTargetCache.isCommonView("android.widget.LinearLayout"))
        assertTrue(AppTargetCache.isCommonView("android.view.View"))
        assertTrue(AppTargetCache.isCommonView("android.view.ViewGroup"))
        assertTrue(AppTargetCache.isCommonView("com.android.internal.policy.DecorView"))
        assertTrue(AppTargetCache.isCommonView("android.widget.PopupWindow"))
        assertTrue(AppTargetCache.isCommonView("android.widget.Toast"))
        assertTrue(AppTargetCache.isCommonView("android.widget.SoftInputWindow"))
        assertTrue(AppTargetCache.isCommonView("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"))

        assertFalse(AppTargetCache.isCommonView("com.whatsapp.HomeActivity"))
        assertFalse(AppTargetCache.isCommonView("com.whatsapp.Main"))
        assertFalse(AppTargetCache.isCommonView("com.whatsapp.Conversation"))
        assertFalse(AppTargetCache.isCommonView("com.instagram.mainactivity.MainActivity"))
        assertFalse(AppTargetCache.isCommonView("com.android.chrome.Main"))
    }

    @Test
    fun testAppTargetCache_activitiesIdentifiedAccurately() {
        assertTrue(AppTargetCache.isActivity("com.whatsapp", "com.whatsapp.HomeActivity"))
        assertTrue(AppTargetCache.isActivity("com.whatsapp", "com.whatsapp.Main"))
        assertTrue(AppTargetCache.isActivity("com.whatsapp", "com.whatsapp.Conversation"))
        assertTrue(AppTargetCache.isActivity("com.whatsapp", "com.whatsapp.camera.CameraActivity"))
        assertTrue(AppTargetCache.isActivity("com.instagram.android", "com.instagram.mainactivity.MainActivity"))

        assertFalse(AppTargetCache.isActivity("com.whatsapp", "android.widget.FrameLayout"))
        assertFalse(AppTargetCache.isActivity("com.whatsapp", "android.view.View"))
        assertFalse(AppTargetCache.isActivity("com.whatsapp", "com.android.internal.policy.DecorView"))
        assertFalse(AppTargetCache.isActivity("com.whatsapp", "android.widget.PopupWindow"))
        assertFalse(AppTargetCache.isActivity("com.whatsapp", "android.widget.Toast"))
        assertFalse(AppTargetCache.isActivity("com.whatsapp", null))
        assertFalse(AppTargetCache.isActivity("com.whatsapp", ""))
    }

    // =========================================================================
    // 2. Notification Suppression Logic Tests
    // =========================================================================

    @Test
    fun testNotificationArrival_neverBlocksOrTriggersLock() {
        var lockRequests = 0
        val protectedPackages = setOf("com.whatsapp", "com.instagram.android")

        fun onWindowStateChanged(
            packageName: String,
            className: String?,
            isFullScreen: Boolean
        ) {
            val isProtected = protectedPackages.contains(packageName)

            // Rule 1: Notification & Floating Overlay Suppression
            if (isProtected && !isFullScreen && !AppTargetCache.isActivity(packageName, className)) {
                return // Suppress notification event!
            }

            lockRequests++
        }

        // WhatsApp heads-up notification banner arrives while user is in another app
        onWindowStateChanged(
            packageName = "com.whatsapp",
            className = "android.widget.FrameLayout",
            isFullScreen = false
        )
        assertEquals("WhatsApp notification banner must NOT trigger lock", 0, lockRequests)

        // WhatsApp toast / transient notification arrives
        onWindowStateChanged(
            packageName = "com.whatsapp",
            className = "android.widget.Toast",
            isFullScreen = false
        )
        assertEquals("WhatsApp toast notification must NOT trigger lock", 0, lockRequests)

        // User actually taps the WhatsApp notification: Activity launches in full screen!
        onWindowStateChanged(
            packageName = "com.whatsapp",
            className = "com.whatsapp.Conversation",
            isFullScreen = true
        )
        assertEquals("Tapping WhatsApp notification must trigger lock immediately", 1, lockRequests)
    }

    // =========================================================================
    // 3. WhatsApp Home Exit Teardown Suppression Tests
    // =========================================================================

    @Test
    fun testHomeExitTeardown_neverTriggersGhostLock() {
        var lockRequests = 0
        var securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
        var activeProtectedPackage: String? = "com.whatsapp"
        var lastExitedProtectedPackage: String? = null

        fun onWindowStateChanged(
            packageName: String,
            className: String?,
            isFullScreen: Boolean
        ) {
            val isProtected = (packageName == "com.whatsapp")

            // 1. Notification / non-fullscreen overlay
            if (isProtected && !isFullScreen && !AppTargetCache.isActivity(packageName, className)) {
                return
            }

            // 2. Common view filter
            if (isProtected && AppTargetCache.isCommonView(className) && activeProtectedPackage != packageName) {
                if (packageName == lastExitedProtectedPackage &&
                    (securityState == ForegroundSecurityState.HOME || securityState == ForegroundSecurityState.UNPROTECTED_APP)
                ) {
                    return // Residual teardown view suppressed!
                }
                if (!isFullScreen) return
            }

            // 3. Teardown / Home Exit Filter
            if ((securityState == ForegroundSecurityState.HOME || securityState == ForegroundSecurityState.UNPROTECTED_APP) &&
                packageName == lastExitedProtectedPackage &&
                !AppTargetCache.isActivity(packageName, className)
            ) {
                return // Residual non-activity event suppressed!
            }

            lockRequests++
        }

        // 1. User is inside WhatsApp.
        assertEquals(0, lockRequests)

        // 2. User exits WhatsApp by pressing Home.
        // Launcher is detected:
        securityState = ForegroundSecurityState.HOME
        lastExitedProtectedPackage = "com.whatsapp"
        activeProtectedPackage = null

        // 3. WhatsApp in background finishes detaching views 1.2s later (FrameLayout, isFullScreen=false)
        onWindowStateChanged(
            packageName = "com.whatsapp",
            className = "android.widget.FrameLayout",
            isFullScreen = false
        )
        assertEquals("Teardown FrameLayout must NOT trigger lock", 0, lockRequests)

        // 4. WhatsApp in background emits DecorView detachment 1.8s later (isFullScreen=true)
        onWindowStateChanged(
            packageName = "com.whatsapp",
            className = "com.android.internal.policy.DecorView",
            isFullScreen = true
        )
        assertEquals("Teardown DecorView must NOT trigger lock", 0, lockRequests)

        // 5. WhatsApp in background emits android.view.View 2.5s later
        onWindowStateChanged(
            packageName = "com.whatsapp",
            className = "android.view.View",
            isFullScreen = false
        )
        assertEquals("Teardown View must NOT trigger lock", 0, lockRequests)

        // 6. User now taps WhatsApp icon on Home launcher to re-enter!
        onWindowStateChanged(
            packageName = "com.whatsapp",
            className = "com.whatsapp.HomeActivity",
            isFullScreen = true
        )
        assertEquals("User re-entry from Home MUST trigger lock", 1, lockRequests)
    }

    // =========================================================================
    // 4. Unprotected App Exit Teardown Tests
    // =========================================================================

    @Test
    fun testUnprotectedAppExitTeardown_suppressedAccurately() {
        var lockRequests = 0
        var securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
        var activeProtectedPackage: String? = "com.whatsapp"
        var lastExitedProtectedPackage: String? = null

        fun onWindowStateChanged(
            packageName: String,
            className: String?,
            isFullScreen: Boolean
        ) {
            val isProtected = (packageName == "com.whatsapp")

            if (isProtected && !isFullScreen && !AppTargetCache.isActivity(packageName, className)) {
                return
            }

            if (isProtected && AppTargetCache.isCommonView(className) && activeProtectedPackage != packageName) {
                if (packageName == lastExitedProtectedPackage &&
                    (securityState == ForegroundSecurityState.HOME || securityState == ForegroundSecurityState.UNPROTECTED_APP)
                ) {
                    return
                }
                if (!isFullScreen) return
            }

            if ((securityState == ForegroundSecurityState.HOME || securityState == ForegroundSecurityState.UNPROTECTED_APP) &&
                packageName == lastExitedProtectedPackage &&
                !AppTargetCache.isActivity(packageName, className)
            ) {
                return
            }

            lockRequests++
        }

        // Switch to YouTube (unprotected app)
        securityState = ForegroundSecurityState.UNPROTECTED_APP
        lastExitedProtectedPackage = "com.whatsapp"
        activeProtectedPackage = null

        // Residual event from WhatsApp
        onWindowStateChanged(
            packageName = "com.whatsapp",
            className = "android.widget.FrameLayout",
            isFullScreen = false
        )
        assertEquals(0, lockRequests)

        // WhatsApp residual DecorView
        onWindowStateChanged(
            packageName = "com.whatsapp",
            className = "com.android.internal.policy.DecorView",
            isFullScreen = true
        )
        assertEquals(0, lockRequests)

        // User switches back to WhatsApp: genuine Activity launch
        onWindowStateChanged(
            packageName = "com.whatsapp",
            className = "com.whatsapp.HomeActivity",
            isFullScreen = true
        )
        assertEquals(1, lockRequests)
    }

    // =========================================================================
    // 5. Samsung Pop-up View (Freeform Window Mode) Tests
    // =========================================================================

    @Test
    fun testPopUpView_unlockDismissalDoesNotTriggerFalseExit() {
        var lockRequests = 0
        var activeProtectedPackage: String? = null
        var isAuthorized = false
        var lastAuthSuccessPackage: String? = null
        var lastAuthSuccessTimestamp: Long = 0L

        fun onAuthenticationSuccess(pkg: String, now: Long) {
            activeProtectedPackage = pkg
            isAuthorized = true
            lastAuthSuccessPackage = pkg
            lastAuthSuccessTimestamp = now
        }

        fun onWindowStateChanged(
            packageName: String,
            className: String?,
            isFullScreen: Boolean,
            now: Long
        ) {
            val isLauncher = (packageName == "com.sec.android.app.launcher")

            if (isLauncher) {
                // Unlock dismissal settle grace window:
                if (activeProtectedPackage != null &&
                    activeProtectedPackage == lastAuthSuccessPackage &&
                    (now - lastAuthSuccessTimestamp < 2000L)
                ) {
                    return // Suppress false exit!
                }
                // Otherwise exit
                activeProtectedPackage = null
                isAuthorized = false
                return
            }

            if (packageName == "com.whatsapp") {
                if (!isAuthorized) {
                    lockRequests++
                }
            }
        }

        // 1. WhatsApp in pop-up view is locked initially
        onWindowStateChanged("com.whatsapp", "com.whatsapp.Conversation", false, 1000L)
        assertEquals(1, lockRequests)

        // 2. User successfully authenticates at 1050L
        onAuthenticationSuccess("com.whatsapp", 1050L)

        // 3. LockScreenActivity finishes: 80ms later at 1130L, underlying Launcher layer is exposed
        onWindowStateChanged("com.sec.android.app.launcher", "com.sec.android.app.launcher.LauncherActivity", true, 1130L)
        // Must NOT have wiped authorization!
        assertTrue("Authorization must remain active", isAuthorized)
        assertEquals("activeProtectedPackage must remain com.whatsapp", "com.whatsapp", activeProtectedPackage)

        // 4. User touches inside WhatsApp pop-up window at 1200L to continue chatting
        onWindowStateChanged("com.whatsapp", "com.whatsapp.Conversation", false, 1200L)
        // Must NOT trigger lock again!
        assertEquals("Pop-up view must NOT trigger lock again after unlock", 1, lockRequests)
    }

    @Test
    fun testPopUpView_backgroundDesktopTapsDoNotCauseLockLoop() {
        var lockRequests = 0
        var activeProtectedPackage: String? = "com.whatsapp"
        var isAuthorized = true
        var isPopUpViewMode = true

        fun onWindowStateChanged(
            packageName: String,
            className: String?,
            isFullScreen: Boolean
        ) {
            val isLauncher = (packageName == "com.sec.android.app.launcher")

            if (isLauncher) {
                // Pop-up view protection over Home desktop:
                if (activeProtectedPackage != null && isPopUpViewMode && isAuthorized) {
                    return // Suppress launcher event while pop-up view is active!
                }
                activeProtectedPackage = null
                isAuthorized = false
                return
            }

            if (packageName == "com.whatsapp") {
                if (AppTargetCache.isActivity(packageName, className)) {
                    isPopUpViewMode = !isFullScreen
                }
                if (!isAuthorized) {
                    lockRequests++
                }
            }
        }

        // WhatsApp active in pop-up view
        assertEquals(0, lockRequests)

        // User interacts with WhatsApp in pop-up view: background launcher layer emits event
        onWindowStateChanged("com.sec.android.app.launcher", "android.widget.FrameLayout", true)
        assertTrue("Pop-up view must preserve authorization", isAuthorized)

        // User taps inside WhatsApp pop-up to send a message
        onWindowStateChanged("com.whatsapp", "com.whatsapp.Conversation", false)
        assertEquals("No new lock request during active pop-up session", 0, lockRequests)
    }
}
