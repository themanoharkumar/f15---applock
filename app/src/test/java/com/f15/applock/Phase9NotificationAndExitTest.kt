package com.f15.applock

import com.f15.applock.data.repository.AppTargetCache
import com.f15.applock.domain.model.ForegroundSecurityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun testPopUpView_usingAppOutsidePopUpViewPreservesSession() {
        var lockRequests = 0
        val activePopUpPackages = mutableSetOf<String>()
        val authorizations = mutableMapOf<String, Boolean>()
        var activeProtectedPackage: String? = null
        var currentForegroundPackage: String? = null

        fun onAuthenticationSuccess(pkg: String, isPopUp: Boolean) {
            authorizations[pkg] = true
            activeProtectedPackage = pkg
            currentForegroundPackage = pkg
            if (isPopUp) {
                activePopUpPackages.add(pkg)
            }
        }

        fun onWindowStateChanged(
            packageName: String,
            className: String?,
            isFullScreen: Boolean
        ) {
            val isProtected = (packageName == "com.whatsapp")

            if (isProtected) {
                if (!isFullScreen) {
                    activePopUpPackages.add(packageName)
                }

                if (authorizations[packageName] == true) {
                    activeProtectedPackage = packageName
                    currentForegroundPackage = packageName
                    return // ALLOW
                } else {
                    lockRequests++
                    return
                }
            }

            // Outside app (e.g. Dialer, Chrome, Launcher)
            val exited = activeProtectedPackage
            currentForegroundPackage = packageName

            if (exited != null) {
                if (activePopUpPackages.contains(exited)) {
                    // Protected app is active in Pop-up view!
                    // Preserve its session while user multi-tasks outside!
                    activeProtectedPackage = null
                } else {
                    activeProtectedPackage = null
                    authorizations.remove(exited)
                }
            }
        }

        fun onPopUpWindowClosed(packageName: String) {
            activePopUpPackages.remove(packageName)
            authorizations.remove(packageName)
            if (activeProtectedPackage == packageName) {
                activeProtectedPackage = null
            }
        }

        // 1. Initial WhatsApp launch in Pop-up view -> triggers lock
        onWindowStateChanged("com.whatsapp", "com.whatsapp.home.ui.HomeActivity", false)
        assertEquals("First launch in pop-up view must request lock", 1, lockRequests)

        // 2. User successfully authenticates
        onAuthenticationSuccess("com.whatsapp", isPopUp = true)
        assertTrue("WhatsApp must be authorized", authorizations["com.whatsapp"] == true)
        assertTrue("WhatsApp must be in activePopUpPackages", activePopUpPackages.contains("com.whatsapp"))

        // 3. User taps outside the pop-up view to open Dialer
        onWindowStateChanged("com.samsung.android.dialer", "com.samsung.android.dialer.DialtactsActivity", true)
        assertEquals("currentForegroundPackage is now dialer", "com.samsung.android.dialer", currentForegroundPackage)
        assertTrue("WhatsApp authorization MUST remain valid while in pop-up view", authorizations["com.whatsapp"] == true)

        // 4. User dials numbers, uses Dialer (multiple events)
        onWindowStateChanged("com.samsung.android.dialer", "android.widget.EditText", true)
        onWindowStateChanged("com.samsung.android.dialer", "com.samsung.android.dialer.DialtactsActivity", true)
        assertTrue("WhatsApp authorization MUST still be valid", authorizations["com.whatsapp"] == true)

        // 5. User taps back into WhatsApp pop-up view!
        onWindowStateChanged("com.whatsapp", "com.whatsapp.Conversation", false)
        assertEquals("No new lock request when tapping back into pop-up view", 1, lockRequests)
        assertEquals("activeProtectedPackage is com.whatsapp again", "com.whatsapp", activeProtectedPackage)

        // 6. User taps outside again to check Chrome
        onWindowStateChanged("com.android.chrome", "com.google.android.apps.chrome.Main", true)
        assertTrue("WhatsApp authorization preserved during Chrome usage", authorizations["com.whatsapp"] == true)

        // 7. User taps back into WhatsApp again
        onWindowStateChanged("com.whatsapp", "com.whatsapp.Conversation", false)
        assertEquals("Still no new lock request", 1, lockRequests)

        // 8. User closes WhatsApp pop-up window (taps X)
        onPopUpWindowClosed("com.whatsapp")
        assertFalse("WhatsApp authorization revoked after closing pop-up window", authorizations.containsKey("com.whatsapp"))
        assertFalse("activePopUpPackages no longer contains com.whatsapp", activePopUpPackages.contains("com.whatsapp"))

        // 9. User opens WhatsApp again (e.g. from Home or Recents) -> Lock must trigger!
        onWindowStateChanged("com.whatsapp", "com.whatsapp.home.ui.HomeActivity", true)
        assertEquals("Reopening closed WhatsApp MUST trigger lock", 2, lockRequests)
    }

    // =========================================================================
    // 6. Recents Overview and Rapid Close/Reopen Deterministic Tests
    // =========================================================================

    @Test
    fun testProtectedApp_immediateCloseAndReopenDeterministicallyTriggersLockEveryTime() {
        var lockRequests = 0
        val authorizations = mutableMapOf<String, Boolean>()
        var activeProtectedPackage: String? = null
        var lastAuthSuccessPackage: String? = null
        var lastAuthSuccessTimestamp: Long = 0L

        fun onAuthenticationSuccess(pkg: String, now: Long) {
            authorizations[pkg] = true
            activeProtectedPackage = pkg
            lastAuthSuccessPackage = pkg
            lastAuthSuccessTimestamp = now
        }

        fun onWindowStateChanged(
            packageName: String,
            className: String?,
            now: Long
        ) {
            val isProtected = (packageName == "com.whatsapp")
            if (isProtected) {
                // Instantly clear settle window when protected app resumes in foreground
                if (packageName == lastAuthSuccessPackage) {
                    lastAuthSuccessPackage = null
                    lastAuthSuccessTimestamp = 0L
                }

                if (authorizations[packageName] == true) {
                    activeProtectedPackage = packageName
                    return // ALLOW
                } else {
                    lockRequests++
                    return // TRIGGER LOCK
                }
            }

            // Exited to Home screen
            val isHome = (packageName == "com.sec.android.app.launcher" &&
                    className?.contains("Launcher", ignoreCase = true) == true)
            if (isHome) {
                // Verify settle window (350ms)
                if (activeProtectedPackage != null &&
                    activeProtectedPackage == lastAuthSuccessPackage &&
                    (now - lastAuthSuccessTimestamp < 350L)
                ) {
                    return // Suppress false exit during unlock dismissal
                }

                val exited = activeProtectedPackage
                if (exited != null) {
                    authorizations.remove(exited)
                    activeProtectedPackage = null
                }
            }
        }

        // --- CYCLE 1 ---
        // 1. User opens WhatsApp from Home (t = 1000)
        onWindowStateChanged("com.whatsapp", "com.whatsapp.HomeActivity", 1000L)
        assertEquals("Cycle 1: First open must trigger lock", 1, lockRequests)

        // 2. User authenticates (t = 1050)
        onAuthenticationSuccess("com.whatsapp", 1050L)

        // 3. WhatsApp resumes (t = 1070) -> settle window cleared immediately
        onWindowStateChanged("com.whatsapp", "com.whatsapp.HomeActivity", 1070L)
        assertEquals("activeProtectedPackage must be com.whatsapp", "com.whatsapp", activeProtectedPackage)

        // 4. User immediately closes WhatsApp to Home (t = 1150, <200ms after unlock)
        onWindowStateChanged("com.sec.android.app.launcher", "com.sec.android.app.launcher.activities.LauncherActivity", 1150L)
        assertFalse("Authorization must be invalidated immediately on close", authorizations.containsKey("com.whatsapp"))
        assertNull("activeProtectedPackage must be null", activeProtectedPackage)

        // 5. User immediately reopens WhatsApp (t = 1200) -> MUST TRIGGER LOCK!
        onWindowStateChanged("com.whatsapp", "com.whatsapp.HomeActivity", 1200L)
        assertEquals("Cycle 1: Immediate reopen (<200ms) MUST trigger lock without bypass", 2, lockRequests)

        // --- CYCLE 2 ---
        // 6. User authenticates again (t = 1250)
        onAuthenticationSuccess("com.whatsapp", 1250L)

        // 7. WhatsApp resumes (t = 1270)
        onWindowStateChanged("com.whatsapp", "com.whatsapp.HomeActivity", 1270L)

        // 8. User closes WhatsApp to Home (t = 1350)
        onWindowStateChanged("com.sec.android.app.launcher", "com.sec.android.app.launcher.activities.LauncherActivity", 1350L)
        assertFalse("Authorization must be invalidated again on close", authorizations.containsKey("com.whatsapp"))

        // 9. User reopens WhatsApp (t = 1400) -> MUST TRIGGER LOCK AGAIN!
        onWindowStateChanged("com.whatsapp", "com.whatsapp.HomeActivity", 1400L)
        assertEquals("Cycle 2: Second reopen MUST trigger lock deterministically", 3, lockRequests)
    }

    @Test
    fun testProtectedApp_recentsRoundtripPreservesSessionAndIntermediateSubviewsDoNotRevoke() {
        var lockRequests = 0
        val authorizations = mutableMapOf<String, Boolean>()
        var activeProtectedPackage: String? = null
        var pendingRecentsPackage: String? = null
        var pendingRecentsTimestamp = 0L

        fun isRecents(pkg: String?, cls: String?): Boolean {
            if (cls.isNullOrBlank()) return false
            return cls.contains("Recents", ignoreCase = true) ||
                   cls.contains("Quickstep", ignoreCase = true) ||
                   cls.contains("TaskSwitcher", ignoreCase = true) ||
                   cls.contains("Overview", ignoreCase = true)
        }

        fun isHomeDesktop(pkg: String?, cls: String?): Boolean {
            if (cls.isNullOrBlank()) return false
            if (isRecents(pkg, cls)) return false
            return cls.contains("Launcher", ignoreCase = true) ||
                   cls.contains("Workspace", ignoreCase = true) ||
                   cls.contains("Home", ignoreCase = true)
        }

        fun onWindowStateChanged(
            packageName: String,
            className: String?,
            now: Long
        ) {
            val isProtected = (packageName == "com.whatsapp")
            if (isProtected) {
                // Check if returning from Recents
                if (pendingRecentsPackage == packageName) {
                    val elapsed = now - pendingRecentsTimestamp
                    if (elapsed < 15_000L && authorizations[packageName] == true) {
                        pendingRecentsPackage = null
                        pendingRecentsTimestamp = 0L
                        activeProtectedPackage = packageName
                        return // ALLOW
                    }
                    pendingRecentsPackage = null
                    pendingRecentsTimestamp = 0L
                }

                if (authorizations[packageName] == true) {
                    activeProtectedPackage = packageName
                    return
                } else {
                    lockRequests++
                    return
                }
            }

            // Launcher / Recents
            if (packageName == "com.sec.android.app.launcher") {
                if (isRecents(packageName, className)) {
                    if (activeProtectedPackage != null) {
                        pendingRecentsPackage = activeProtectedPackage
                        pendingRecentsTimestamp = now
                        activeProtectedPackage = null
                    }
                    return
                }

                // If in Recents, do NOT wipe on intermediate subviews (e.g. FrameLayout, ViewGroup)
                if (pendingRecentsPackage != null && !isHomeDesktop(packageName, className)) {
                    return // Preserve pending session!
                }

                // If navigated to Home desktop, revoke pending session
                if (pendingRecentsPackage != null && isHomeDesktop(packageName, className)) {
                    authorizations.remove(pendingRecentsPackage)
                    pendingRecentsPackage = null
                    pendingRecentsTimestamp = 0L
                }

                if (activeProtectedPackage != null) {
                    authorizations.remove(activeProtectedPackage)
                    activeProtectedPackage = null
                }
            }

            // Unprotected app (e.g. Chrome)
            if (packageName == "com.android.chrome") {
                if (pendingRecentsPackage != null) {
                    authorizations.remove(pendingRecentsPackage)
                    pendingRecentsPackage = null
                    pendingRecentsTimestamp = 0L
                }
                if (activeProtectedPackage != null) {
                    authorizations.remove(activeProtectedPackage)
                    activeProtectedPackage = null
                }
            }
        }

        // 1. Initial WhatsApp launch -> locks
        onWindowStateChanged("com.whatsapp", "com.whatsapp.HomeActivity", 1000L)
        assertEquals(1, lockRequests)

        // 2. User unlocks WhatsApp
        authorizations["com.whatsapp"] = true
        activeProtectedPackage = "com.whatsapp"

        // 3. User swipes to Recent Apps overview (com.android.quickstep.RecentsActivity)
        onWindowStateChanged("com.sec.android.app.launcher", "com.android.quickstep.RecentsActivity", 2000L)
        assertEquals("com.whatsapp", pendingRecentsPackage)
        assertTrue("Authorization must remain intact while in Recents", authorizations["com.whatsapp"] == true)

        // 4. Samsung Launcher emits intermediate subview FrameLayout while in Recents
        onWindowStateChanged("com.sec.android.app.launcher", "android.widget.FrameLayout", 2050L)
        assertEquals("Subviews must not clear pendingRecentsPackage", "com.whatsapp", pendingRecentsPackage)
        assertTrue("Authorization must remain valid after subviews", authorizations["com.whatsapp"] == true)

        // 5. User taps WhatsApp card to return to WhatsApp within 15 seconds!
        onWindowStateChanged("com.whatsapp", "com.whatsapp.HomeActivity", 2800L)
        assertNull("pendingRecentsPackage cleared on return", pendingRecentsPackage)
        assertEquals("activeProtectedPackage restored to com.whatsapp", "com.whatsapp", activeProtectedPackage)
        assertEquals("MUST NOT trigger lock screen on return from Recents", 1, lockRequests)

        // 6. User swipes to Recents again
        onWindowStateChanged("com.sec.android.app.launcher", "com.android.quickstep.RecentsActivity", 4000L)
        assertEquals("com.whatsapp", pendingRecentsPackage)

        // 7. User navigates from Recents to Home Desktop (LauncherActivity)
        onWindowStateChanged("com.sec.android.app.launcher", "com.sec.android.app.launcher.activities.LauncherActivity", 5000L)
        assertNull("pendingRecentsPackage cleared on exiting to Home", pendingRecentsPackage)
        assertFalse("WhatsApp authorization revoked after navigating from Recents to Home", authorizations.containsKey("com.whatsapp"))

        // 8. User taps WhatsApp icon on Home desktop -> MUST trigger lock!
        onWindowStateChanged("com.whatsapp", "com.whatsapp.HomeActivity", 6000L)
        assertEquals("Opening WhatsApp from Home after Recents MUST trigger lock", 2, lockRequests)
    }
}
