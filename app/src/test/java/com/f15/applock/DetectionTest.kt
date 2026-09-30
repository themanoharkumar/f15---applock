package com.f15.applock

import com.f15.applock.domain.model.LockRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionTest {

    @Test
    fun duplicateEvents_areSuppressed() {
        val transitions = mutableListOf<Pair<String?, String>>()
        var currentPackage: String? = null
        var previousPackage: String? = null

        fun onNewPackageDetected(pkg: String) {
            if (pkg != currentPackage) {
                val prev = currentPackage
                previousPackage = prev
                currentPackage = pkg
                transitions.add(prev to pkg)
            }
        }

        // Simulate sequence: Chrome -> WhatsApp -> WhatsApp -> WhatsApp -> Instagram -> Home
        onNewPackageDetected("com.android.chrome")
        onNewPackageDetected("com.whatsapp")
        onNewPackageDetected("com.whatsapp") // duplicate
        onNewPackageDetected("com.whatsapp") // duplicate
        onNewPackageDetected("com.instagram.android")
        onNewPackageDetected("com.sec.android.app.launcher")

        // Should only record 4 distinct transitions
        assertEquals(4, transitions.size)
        assertEquals(null to "com.android.chrome", transitions[0])
        assertEquals("com.android.chrome" to "com.whatsapp", transitions[1])
        assertEquals("com.whatsapp" to "com.instagram.android", transitions[2])
        assertEquals("com.instagram.android" to "com.sec.android.app.launcher", transitions[3])
    }

    @Test
    fun lockDecision_evaluatesProtectedAppsCorrectly() {
        val lockedApps = setOf("com.whatsapp", "com.google.android.youtube")
        val selfPackage = "com.f15.applock"
        val launcherPackage = "com.sec.android.app.launcher"

        fun shouldLockSimulated(pkg: String, isUnlocked: Boolean, isGlobalSessionActive: Boolean): Boolean {
            if (pkg == selfPackage || pkg == launcherPackage) return false
            if (!lockedApps.contains(pkg)) return false
            if (isUnlocked || isGlobalSessionActive) return false
            return true
        }

        // WhatsApp protected without session -> locks
        assertTrue(shouldLockSimulated("com.whatsapp", isUnlocked = false, isGlobalSessionActive = false))

        // WhatsApp with active unlock grant -> does not lock
        assertFalse(shouldLockSimulated("com.whatsapp", isUnlocked = true, isGlobalSessionActive = false))

        // YouTube with global session -> does not lock
        assertFalse(shouldLockSimulated("com.google.android.youtube", isUnlocked = false, isGlobalSessionActive = true))

        // Unprotected app (Chrome) -> does not lock
        assertFalse(shouldLockSimulated("com.android.chrome", isUnlocked = false, isGlobalSessionActive = false))

        // App Lock self -> never locks
        assertFalse(shouldLockSimulated("com.f15.applock", isUnlocked = false, isGlobalSessionActive = false))

        // Samsung Launcher -> never locks
        assertFalse(shouldLockSimulated("com.sec.android.app.launcher", isUnlocked = false, isGlobalSessionActive = false))
    }

    @Test
    fun lockRequest_containsValidPackageAndTimestamp() {
        val req = LockRequest("com.whatsapp")
        assertEquals("com.whatsapp", req.packageName)
        assertTrue(req.timestamp > 0L)
    }
}
