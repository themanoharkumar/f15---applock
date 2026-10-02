package com.f15.applock

import android.os.Bundle
import com.f15.applock.device.DeviceOwnerIntegrityResult
import com.f15.applock.knox.PolicyTransactionValidator
import com.f15.applock.knox.PolicyValidationResult
import com.f15.applock.security.SecurityEventLogger
import com.f15.applock.security.SecurityEventSeverity
import com.f15.applock.security.SecurityEventType
import com.f15.applock.security.SecurityIntegrityManager
import com.f15.applock.security.SecurityPostureState
import com.f15.applock.security.SecurityState
import com.f15.applock.security.SecurityStateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 8 Unit Tests: Anti-Tamper, Security Hardening, Policy Validation,
 * Centralized Security State Machine, and Keystore HMAC Integrity.
 */
class Phase8SecurityHardeningTest {

    @Before
    fun setUp() {
        SecurityEventLogger.clear()
    }

    // =========================================================================
    // 1. Centralized Security State Priority Evaluation
    // Priority: RECOVERY_REQUIRED > COMPROMISED > DEGRADED > SECURE
    // =========================================================================

    @Test
    fun testSecurityStatePriority_RecoveryRequiredOverAll() {
        // Even if other flags are passing or failing, a broad global restriction demands RECOVERY_REQUIRED
        val state = SecurityStateManager.computeSecurityState(
            isAppIdentityValid = true,
            deviceOwnerResult = DeviceOwnerIntegrityResult.VerifiedActive,
            isScopeClean = false,
            hasBroadRestrictions = true, // Emergency condition!
            isAccessibilityRunning = true,
            isMasterPinSet = true,
            isConfigValid = true
        )

        assertEquals("Broad global restriction must trigger RECOVERY_REQUIRED", SecurityPostureState.RECOVERY_REQUIRED, state)
    }

    @Test
    fun testSecurityStatePriority_CompromisedOverDegraded() {
        // Device Owner lost + Accessibility stopped: COMPROMISED must take precedence over DEGRADED
        val state = SecurityStateManager.computeSecurityState(
            isAppIdentityValid = true,
            deviceOwnerResult = DeviceOwnerIntegrityResult.Lost("DO revoked"), // Compromised condition
            isScopeClean = true,
            hasBroadRestrictions = false,
            isAccessibilityRunning = false, // Degraded condition
            isMasterPinSet = true,
            isConfigValid = true
        )

        assertEquals("Privilege loss (DO Lost) must evaluate to RECOVERY_REQUIRED or COMPROMISED", SecurityPostureState.RECOVERY_REQUIRED, state)
    }

    @Test
    fun testSecurityStatePriority_ConfigTamperedEvaluatesToCompromised() {
        val state = SecurityStateManager.computeSecurityState(
            isAppIdentityValid = true,
            deviceOwnerResult = DeviceOwnerIntegrityResult.VerifiedActive,
            isScopeClean = true,
            hasBroadRestrictions = false,
            isAccessibilityRunning = true,
            isMasterPinSet = true,
            isConfigValid = false // Tampered!
        )

        assertEquals("Tampered configuration must evaluate to COMPROMISED", SecurityPostureState.COMPROMISED, state)
    }

    @Test
    fun testSecurityStatePriority_DegradedWhenServiceNotRunning() {
        val state = SecurityStateManager.computeSecurityState(
            isAppIdentityValid = true,
            deviceOwnerResult = DeviceOwnerIntegrityResult.VerifiedActive,
            isScopeClean = true,
            hasBroadRestrictions = false,
            isAccessibilityRunning = false, // Inactive
            isMasterPinSet = true,
            isConfigValid = true
        )

        assertEquals("Inactive service without breach must evaluate to DEGRADED", SecurityPostureState.DEGRADED, state)
    }

    @Test
    fun testSecurityStatePriority_SecureWhenAllChecksPass() {
        val state = SecurityStateManager.computeSecurityState(
            isAppIdentityValid = true,
            deviceOwnerResult = DeviceOwnerIntegrityResult.VerifiedActive,
            isScopeClean = true,
            hasBroadRestrictions = false,
            isAccessibilityRunning = true,
            isMasterPinSet = true,
            isConfigValid = true
        )

        assertEquals("When all checks pass, state must be SECURE", SecurityPostureState.SECURE, state)
    }

    // =========================================================================
    // 2. Policy Transaction Validator Tests
    // Rejects wildcards, non-AppLock packages, empty lists, and broad restrictions
    // =========================================================================

    @Test
    fun testPolicyTransactionValidator_ValidAppLockTargetAccepted() {
        val validation = PolicyTransactionValidator.validateSelfProtectionTarget("com.f15.applock")
        assertTrue("com.f15.applock must be accepted", validation.isApproved)
    }

    @Test
    fun testPolicyTransactionValidator_RejectsThirdPartyPackage() {
        val validation = PolicyTransactionValidator.validateSelfProtectionTarget("com.sec.android.app.camera")
        assertFalse("Third party package must be rejected", validation.isApproved)
        assertTrue(validation is PolicyValidationResult.Rejected)
        assertTrue((validation as PolicyValidationResult.Rejected).reason.contains("broad"))
    }

    @Test
    fun testPolicyTransactionValidator_RejectsWildcard() {
        val validation = PolicyTransactionValidator.validateSelfProtectionTarget("*")
        assertFalse("Wildcard must be strictly rejected", validation.isApproved)
        assertTrue(validation is PolicyValidationResult.Rejected)
    }

    @Test
    fun testPolicyTransactionValidator_RejectsEmptyPackageList() {
        val validation = PolicyTransactionValidator.validatePackageList(emptyList())
        assertFalse("Empty package list must be rejected", validation.isApproved)
    }

    @Test
    fun testPolicyTransactionValidator_RejectsMultiPackageList() {
        val validation = PolicyTransactionValidator.validatePackageList(listOf("com.f15.applock", "com.whatsapp"))
        assertFalse("Multi-package list must be rejected to prevent collateral locking", validation.isApproved)
    }

    // =========================================================================
    // 3. HMAC Canonical Serialization & Constant-Time Validation
    // =========================================================================

    @Test
    fun testCanonicalSerializationDeterministic() {
        val payload1 = SecurityIntegrityManager.canonicalize(
            packages = setOf("com.whatsapp", "com.google.android.youtube"),
            biometricEnabled = true,
            timeoutSeconds = 60
        )
        val payload2 = SecurityIntegrityManager.canonicalize(
            packages = setOf("com.google.android.youtube", "com.whatsapp"), // Different input order
            biometricEnabled = true,
            timeoutSeconds = 60
        )

        // Sorted deterministic output
        assertEquals("Canonical payload must be order-independent for packages", payload1, payload2)
        assertTrue("Must include version prefix", payload1.startsWith("V1|"))
        assertTrue("Must include packages sorted", payload1.contains("com.google.android.youtube,com.whatsapp"))
    }

    @Test
    fun testConstantTimeComparison() {
        val str1 = "f8a42b109e23"
        val str2 = "f8a42b109e23"
        val str3 = "f8a42b109e24" // 1 char difference

        assertTrue(SecurityIntegrityManager.constantTimeEquals(str1, str2))
        assertFalse(SecurityIntegrityManager.constantTimeEquals(str1, str3))
        assertFalse(SecurityIntegrityManager.constantTimeEquals(str1, "short"))
    }

    // =========================================================================
    // 4. Security Event Logger Bounded Queue & FIFO Eviction
    // =========================================================================

    @Test
    fun testSecurityEventLoggerBoundedCapacity() {
        SecurityEventLogger.clear()

        // Log 200 events to test bounded FIFO capacity (150 limit)
        for (i in 1..200) {
            SecurityEventLogger.log(
                type = SecurityEventType.PROTECTED_APP_OPENED,
                details = "Event #$i",
                severity = SecurityEventSeverity.INFO,
                component = "Enforcement",
                result = "SUCCESS"
            )
        }

        val events = SecurityEventLogger.getRecentEvents(300)
        assertEquals("Audit log must enforce max capacity of 150", 150, events.size)

        // The newest event should be #200
        val newestEvent = events.first()
        assertEquals("Newest event must be #200", "Event #200", newestEvent.details)

        // The oldest retained event should be #51 (oldest 50 evicted)
        val oldestRetained = events.last()
        assertEquals("Oldest retained event must be #51 after FIFO eviction", "Event #51", oldestRetained.details)
    }

    @Test
    fun testSecurityEventLoggerSanitizesInput() {
        SecurityEventLogger.clear()
        SecurityEventLogger.log(
            type = SecurityEventType.AUTHENTICATION_FAILURE,
            details = "Authentication attempt with PIN 1234",
            severity = SecurityEventSeverity.WARNING,
            component = "PinAuth",
            result = "FAILURE"
        )

        val events = SecurityEventLogger.getRecentEvents(10)
        assertFalse(events.isEmpty())
        val logged = events[0]
        assertNotNull(logged.formattedTime)
        assertFalse("Must never log PIN numbers", logged.details.contains("1234"))
    }
}
