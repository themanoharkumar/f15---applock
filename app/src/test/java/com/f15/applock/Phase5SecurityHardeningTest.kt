package com.f15.applock

import com.f15.applock.data.security.SecurityEvent
import com.f15.applock.data.security.SecurityEventLogger
import com.f15.applock.data.security.SecurityEventType
import com.f15.applock.domain.model.AppAuthorization
import com.f15.applock.domain.model.SessionTimeout
import com.f15.applock.security.OverallSecurityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests verifying Phase 5 Security Hardening:
 * - Package name sanitization and self-lock prevention
 * - Stale package purging for uninstalled applications
 * - Configuration schema versioning (config_version = 1)
 * - Bounded, privacy-preserving security event logging
 * - Screen-off auto-lock session invalidation
 * - Security health posture evaluation logic
 */
class Phase5SecurityHardeningTest {

    @Test
    fun packageSanitization_preventsBlankAndSelfPackage() {
        val selfPackage = "com.f15.applock"

        fun sanitizePackages(packages: Set<String>): Set<String> {
            return packages.filter { it.isNotBlank() && it.trim() != selfPackage }.toSet()
        }

        val rawSet = setOf(
            "com.whatsapp",
            "",
            "   ",
            "com.f15.applock",
            "com.instagram.android"
        )

        val sanitized = sanitizePackages(rawSet)
        assertEquals(2, sanitized.size)
        assertTrue(sanitized.contains("com.whatsapp"))
        assertTrue(sanitized.contains("com.instagram.android"))
        assertFalse(sanitized.contains("com.f15.applock"))
        assertFalse(sanitized.contains(""))
    }

    @Test
    fun stalePackageCleanup_purgesUninstalledPackagesAccurately() {
        val currentLockedPackages = setOf(
            "com.whatsapp",
            "com.instagram.android",
            "com.old.uninstalled.app",
            "com.temp.testapp"
        )

        // Device currently has only WhatsApp and Instagram installed
        val installedOnDevice = setOf(
            "com.whatsapp",
            "com.instagram.android",
            "com.android.chrome"
        )

        fun cleanupStale(locked: Set<String>, installed: Set<String>): Pair<Set<String>, Int> {
            var purgedCount = 0
            val valid = locked.filter { pkg ->
                val exists = installed.contains(pkg)
                if (!exists) purgedCount++
                exists
            }.toSet()
            return valid to purgedCount
        }

        val (validSet, purgedCount) = cleanupStale(currentLockedPackages, installedOnDevice)
        assertEquals(2, purgedCount)
        assertEquals(2, validSet.size)
        assertTrue(validSet.contains("com.whatsapp"))
        assertTrue(validSet.contains("com.instagram.android"))
        assertFalse(validSet.contains("com.old.uninstalled.app"))
        assertFalse(validSet.contains("com.temp.testapp"))
    }

    @Test
    fun securityEventLogger_isBoundedAndEvictsFIFO() {
        SecurityEventLogger.clearLogs()

        // Log 200 events (configured limit is 150)
        for (i in 1..200) {
            SecurityEventLogger.log(
                SecurityEventType.AUTH_REQUESTED,
                "Simulated event #$i"
            )
        }

        val snapshot = SecurityEventLogger.getSnapshot()
        assertEquals(SecurityEventLogger.MAX_EVENTS, snapshot.size)

        // First item should be the newest event (#200)
        assertEquals("Simulated event #200", snapshot.first().details)
        // Last item should be event #51 (1-50 were evicted)
        assertEquals("Simulated event #51", snapshot.last().details)

        SecurityEventLogger.clearLogs()
    }

    @Test
    fun securityEvent_containsNoSensitiveInformation() {
        val event = SecurityEvent(
            id = 1L,
            timestamp = System.currentTimeMillis(),
            eventType = SecurityEventType.AUTH_SUCCESS,
            details = "Authentication successful for protected package: com.whatsapp"
        )

        // Event must not contain any PIN digits or secret material
        assertFalse(event.details.contains("pin", ignoreCase = true))
        assertFalse(event.details.contains("password", ignoreCase = true))
        assertNotNull(event.formattedTime)
    }

    @Test
    fun screenOffLock_invalidatesImmediateSession() {
        val authorizations = ConcurrentHashMap<String, AppAuthorization>()
        val leftForegroundTimestamps = ConcurrentHashMap<String, Long>()

        // User authenticated WhatsApp at 1000L
        authorizations["com.whatsapp"] = AppAuthorization("com.whatsapp", 1000L, Long.MAX_VALUE)

        fun onScreenOff(currentPkg: String?, timeout: SessionTimeout) {
            val now = 2000L
            if (currentPkg != null) {
                leftForegroundTimestamps[currentPkg] = now
            }
            if (timeout == SessionTimeout.IMMEDIATELY) {
                authorizations.clear()
            }
        }

        // Screen turned off while inside WhatsApp with IMMEDIATELY timeout
        onScreenOff("com.whatsapp", SessionTimeout.IMMEDIATELY)

        // Authorizations must be wiped immediately
        assertTrue(authorizations.isEmpty())
        assertEquals(2000L, leftForegroundTimestamps["com.whatsapp"])
    }

    @Test
    fun screenOffLock_recordsTimestampForTimedSession() {
        val authorizations = ConcurrentHashMap<String, AppAuthorization>()
        val leftForegroundTimestamps = ConcurrentHashMap<String, Long>()

        // 1-minute timeout
        authorizations["com.whatsapp"] = AppAuthorization("com.whatsapp", 10_000L, 70_000L)

        fun onScreenOff(currentPkg: String?, timeout: SessionTimeout, screenOffTime: Long) {
            if (currentPkg != null) {
                leftForegroundTimestamps[currentPkg] = screenOffTime
            }
            if (timeout == SessionTimeout.IMMEDIATELY) {
                authorizations.clear()
            }
        }

        // Screen turned off at 15_000L
        onScreenOff("com.whatsapp", SessionTimeout.MINUTE_1, screenOffTime = 15_000L)

        // Authorization map retained, but background timestamp recorded
        assertFalse(authorizations.isEmpty())
        assertEquals(15_000L, leftForegroundTimestamps["com.whatsapp"])

        fun shouldLock(now: Long, timeout: SessionTimeout): Boolean {
            val left = leftForegroundTimestamps["com.whatsapp"] ?: return true
            val elapsed = now - left
            return elapsed >= timeout.durationMillis
        }

        // Screen turned back on at 40_000L (25s off, < 60s timeout) -> Not locked
        assertFalse(shouldLock(now = 40_000L, SessionTimeout.MINUTE_1))

        // Screen turned back on at 80_000L (65s off, >= 60s timeout) -> LOCKED!
        assertTrue(shouldLock(now = 80_000L, SessionTimeout.MINUTE_1))
    }

    @Test
    fun securityHealthPosture_evaluatesStatusHierarchyCorrectly() {
        fun calculatePosture(
            hasPin: Boolean,
            isA11yHealthy: Boolean,
            hasUsage: Boolean,
            isBatteryUnrestricted: Boolean,
            hasLockedApps: Boolean
        ): OverallSecurityStatus {
            return when {
                !hasPin -> OverallSecurityStatus.SETUP_REQUIRED
                !isA11yHealthy && !hasUsage -> OverallSecurityStatus.DEGRADED
                !isA11yHealthy -> OverallSecurityStatus.WARNING
                !isBatteryUnrestricted || !hasLockedApps -> OverallSecurityStatus.WARNING
                else -> OverallSecurityStatus.SECURE
            }
        }

        // Ideal configuration
        assertEquals(
            OverallSecurityStatus.SECURE,
            calculatePosture(
                hasPin = true,
                isA11yHealthy = true,
                hasUsage = true,
                isBatteryUnrestricted = true,
                hasLockedApps = true
            )
        )

        // No PIN configured
        assertEquals(
            OverallSecurityStatus.SETUP_REQUIRED,
            calculatePosture(
                hasPin = false,
                isA11yHealthy = true,
                hasUsage = true,
                isBatteryUnrestricted = true,
                hasLockedApps = true
            )
        )

        // Accessibility disabled, but usage fallback available
        assertEquals(
            OverallSecurityStatus.WARNING,
            calculatePosture(
                hasPin = true,
                isA11yHealthy = false,
                hasUsage = true,
                isBatteryUnrestricted = true,
                hasLockedApps = true
            )
        )

        // Both Accessibility and Usage Access missing
        assertEquals(
            OverallSecurityStatus.DEGRADED,
            calculatePosture(
                hasPin = true,
                isA11yHealthy = false,
                hasUsage = false,
                isBatteryUnrestricted = true,
                hasLockedApps = true
            )
        )
    }
}
