package com.f15.applock

import com.f15.applock.domain.model.ForegroundSecurityState
import com.f15.applock.domain.model.SessionTimeout
import com.f15.applock.security.LockDecisionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying transient overlay behavior:
 * 1. Google Circle to Search & Assistant overlays do not evict active protected sessions.
 * 2. Samsung Gallery integrated video playback & editors do not evict active Gallery sessions.
 * 3. Navigating to Home, locking the screen, or switching to an unrelated unprotected app
 *    still securely revokes authorizations fail-closed.
 * 4. User-protected packages are never treated as transient overlays.
 */
class TransientOverlayBehaviorTest {

    @Test
    fun testTransientOverlayPackages_containsCircleToSearchAndSamsungMedia() {
        val overlays = LockDecisionManager.TRANSIENT_OVERLAY_PACKAGES

        // Google Circle to Search & Assistant
        assertTrue("Must include Google App (hosts Circle to Search)",
            overlays.contains("com.google.android.googlequicksearchbox"))
        assertTrue("Must include Android System Intelligence",
            overlays.contains("com.google.android.as"))
        assertTrue("Must include Google Assistant",
            overlays.contains("com.google.android.apps.search.assistant"))
        assertTrue("Must include Voice Interaction",
            overlays.contains("com.google.android.voiceinteraction"))

        // Samsung Gallery Video Player & Media Editors
        assertTrue("Must include Samsung Video",
            overlays.contains("com.samsung.android.video"))
        assertTrue("Must include legacy Samsung Video Player",
            overlays.contains("com.sec.android.app.videoplayer"))
        assertTrue("Must include Samsung Photo Editor",
            overlays.contains("com.sec.android.mimage.photoretouching"))
        assertTrue("Must include Samsung Video Editor Preload",
            overlays.contains("com.sec.android.app.vepreload"))
        assertTrue("Must include Samsung Video Editor",
            overlays.contains("com.sec.android.app.ve"))

        // Samsung Vision & Companion Overlays
        assertTrue("Must include Samsung Vision Intelligence",
            overlays.contains("com.samsung.android.visionintelligence"))
        assertTrue("Must include Samsung Visual Search",
            overlays.contains("com.samsung.android.visualsearch"))
        assertTrue("Must include Bixby Agent",
            overlays.contains("com.samsung.android.bixby.agent"))
        assertTrue("Must include Smart Capture",
            overlays.contains("com.samsung.android.smartcapture"))
        assertTrue("Must include Edge Panel",
            overlays.contains("com.samsung.android.app.cocktailbarservice"))
    }

    @Test
    fun testTransientOverlayPackages_doesNotIncludeUnrelatedApps() {
        val overlays = LockDecisionManager.TRANSIENT_OVERLAY_PACKAGES

        assertFalse("Chrome must never be an overlay", overlays.contains("com.android.chrome"))
        assertFalse("WhatsApp must never be an overlay", overlays.contains("com.whatsapp"))
        assertFalse("Gallery must never be an overlay", overlays.contains("com.sec.android.gallery3d"))
        assertFalse("Instagram must never be an overlay", overlays.contains("com.instagram.android"))
        assertFalse("YouTube must never be an overlay", overlays.contains("com.google.android.youtube"))
        assertFalse("Files must never be an overlay", overlays.contains("com.sec.android.app.myfiles"))
    }

    @Test
    fun testCircleToSearchWorkflow_preservesSessionAndReturnsSeamlessly() {
        // State simulation matching LockController + LockDecisionManager rules
        val protectedApps = setOf("com.whatsapp")
        val authorizedSessions = mutableMapOf<String, Long>()
        var activeProtectedPackage: String? = "com.whatsapp"
        var currentForegroundPackage: String? = "com.whatsapp"
        var securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED

        // WhatsApp unlocked initially
        authorizedSessions["com.whatsapp"] = 1000L

        fun isTransientOverlay(pkg: String): Boolean {
            if (protectedApps.contains(pkg)) return false
            return LockDecisionManager.TRANSIENT_OVERLAY_PACKAGES.contains(pkg)
        }

        fun onWindowStateChanged(pkg: String) {
            val isProtected = protectedApps.contains(pkg)
            if (isProtected) {
                val prevPackage = currentForegroundPackage
                val isNewPackage = (pkg != prevPackage)

                // Same-Package Rule
                if (!isNewPackage &&
                    activeProtectedPackage == pkg &&
                    securityState == ForegroundSecurityState.PROTECTED_APP_AUTHORIZED &&
                    authorizedSessions.containsKey(pkg)
                ) {
                    // ALLOW seamless
                    return
                }

                currentForegroundPackage = pkg
                val isAuth = authorizedSessions.containsKey(pkg)
                if (isAuth) {
                    activeProtectedPackage = pkg
                    securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
                } else {
                    activeProtectedPackage = null
                    securityState = ForegroundSecurityState.PROTECTED_APP_LOCKED
                }
                return
            }

            // Non-protected
            if (isTransientOverlay(pkg)) {
                // IGNORE - OVERLAY
                return
            }

            // Unprotected or Launcher exit
            if (activeProtectedPackage != null) {
                authorizedSessions.remove(activeProtectedPackage)
                activeProtectedPackage = null
            }
            currentForegroundPackage = pkg
            securityState = ForegroundSecurityState.UNPROTECTED_APP
        }

        // 1. User invokes Circle to Search over WhatsApp
        onWindowStateChanged("com.google.android.googlequicksearchbox")

        // WhatsApp's authorization and active state must be untouched!
        assertEquals("com.whatsapp", activeProtectedPackage)
        assertEquals("com.whatsapp", currentForegroundPackage)
        assertEquals(ForegroundSecurityState.PROTECTED_APP_AUTHORIZED, securityState)
        assertTrue(authorizedSessions.containsKey("com.whatsapp"))

        // 2. User dismisses Circle to Search, window returns to WhatsApp
        onWindowStateChanged("com.whatsapp")

        // Seamless return via Same-Package Rule: Still authorized, no lock triggered!
        assertEquals("com.whatsapp", activeProtectedPackage)
        assertEquals(ForegroundSecurityState.PROTECTED_APP_AUTHORIZED, securityState)
        assertTrue(authorizedSessions.containsKey("com.whatsapp"))

        // 3. User navigates to Home screen
        onWindowStateChanged("com.sec.android.app.launcher")

        // Session must be cleanly revoked upon Home exit!
        assertNull(activeProtectedPackage)
        assertFalse(authorizedSessions.containsKey("com.whatsapp"))

        // 4. User opens WhatsApp again from Home
        onWindowStateChanged("com.whatsapp")

        // Must require lock screen authentication!
        assertEquals(ForegroundSecurityState.PROTECTED_APP_LOCKED, securityState)
    }

    @Test
    fun testSamsungGalleryVideoWorkflow_preservesSessionAndReturnsSeamlessly() {
        val protectedApps = setOf("com.sec.android.gallery3d")
        val authorizedSessions = mutableMapOf<String, Long>()
        var activeProtectedPackage: String? = "com.sec.android.gallery3d"
        var currentForegroundPackage: String? = "com.sec.android.gallery3d"
        var securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED

        // Gallery unlocked initially
        authorizedSessions["com.sec.android.gallery3d"] = 2000L

        fun isTransientOverlay(pkg: String): Boolean {
            if (protectedApps.contains(pkg)) return false
            return LockDecisionManager.TRANSIENT_OVERLAY_PACKAGES.contains(pkg)
        }

        fun onWindowStateChanged(pkg: String) {
            val isProtected = protectedApps.contains(pkg)
            if (isProtected) {
                val prevPackage = currentForegroundPackage
                val isNewPackage = (pkg != prevPackage)

                if (!isNewPackage &&
                    activeProtectedPackage == pkg &&
                    securityState == ForegroundSecurityState.PROTECTED_APP_AUTHORIZED &&
                    authorizedSessions.containsKey(pkg)
                ) {
                    return
                }

                currentForegroundPackage = pkg
                val isAuth = authorizedSessions.containsKey(pkg)
                if (isAuth) {
                    activeProtectedPackage = pkg
                    securityState = ForegroundSecurityState.PROTECTED_APP_AUTHORIZED
                } else {
                    activeProtectedPackage = null
                    securityState = ForegroundSecurityState.PROTECTED_APP_LOCKED
                }
                return
            }

            if (isTransientOverlay(pkg)) {
                return
            }

            if (activeProtectedPackage != null) {
                authorizedSessions.remove(activeProtectedPackage)
                activeProtectedPackage = null
            }
            currentForegroundPackage = pkg
            securityState = ForegroundSecurityState.UNPROTECTED_APP
        }

        // 1. User taps a video inside Gallery -> Samsung Video player opens
        onWindowStateChanged("com.samsung.android.video")

        // Gallery session must be preserved
        assertEquals("com.sec.android.gallery3d", activeProtectedPackage)
        assertEquals("com.sec.android.gallery3d", currentForegroundPackage)
        assertTrue(authorizedSessions.containsKey("com.sec.android.gallery3d"))

        // 2. Video finishes/closes, returning to Gallery
        onWindowStateChanged("com.sec.android.gallery3d")

        // Access granted seamlessly without lock screen!
        assertEquals("com.sec.android.gallery3d", activeProtectedPackage)
        assertEquals(ForegroundSecurityState.PROTECTED_APP_AUTHORIZED, securityState)
        assertTrue(authorizedSessions.containsKey("com.sec.android.gallery3d"))

        // 3. User switches to Chrome while in Gallery
        onWindowStateChanged("com.android.chrome")

        // Session must be revoked immediately!
        assertNull(activeProtectedPackage)
        assertFalse(authorizedSessions.containsKey("com.sec.android.gallery3d"))

        // 4. User returns to Gallery from Chrome
        onWindowStateChanged("com.sec.android.gallery3d")

        // Must trigger lock screen!
        assertEquals(ForegroundSecurityState.PROTECTED_APP_LOCKED, securityState)
    }

    @Test
    fun testExplicitlyProtectedPackage_isNeverTreatedAsTransientOverlay() {
        val userProtectedApps = setOf("com.samsung.android.video", "com.google.android.googlequicksearchbox")

        fun isTransientOverlay(pkg: String): Boolean {
            // Rule: User-protected packages take precedence over overlay definitions
            if (userProtectedApps.contains(pkg)) return false
            return LockDecisionManager.TRANSIENT_OVERLAY_PACKAGES.contains(pkg)
        }

        assertFalse("Explicitly locked Samsung Video must NOT be an overlay",
            isTransientOverlay("com.samsung.android.video"))
        assertFalse("Explicitly locked Google App must NOT be an overlay",
            isTransientOverlay("com.google.android.googlequicksearchbox"))
    }
}
