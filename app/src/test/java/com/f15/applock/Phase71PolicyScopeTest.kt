package com.f15.applock

import com.f15.applock.knox.KnoxPolicyState
import com.f15.applock.knox.PolicyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7.1 Unit Tests: Policy Scope & Global Restriction Isolation.
 *
 * Validates:
 * 1. PolicyStatus.Disabled exists and displays as "Disabled".
 * 2. KnoxPolicyState properly isolates App Lock self-protection from Global restrictions.
 * 3. Default state has global restrictions marked as Disabled.
 * 4. Self-protection active does NOT imply global restrictions active.
 */
class Phase71PolicyScopeTest {

    @Test
    fun testPolicyStatusDisabledDisplayLabel() {
        val disabled = PolicyStatus.Disabled
        assertEquals("Disabled", disabled.displayLabel)
        assertFalse("Disabled should not count as active enforcement", disabled.isConfirmedActive)
    }

    @Test
    fun testDefaultPolicyStateHasGlobalRestrictionsDisabled() {
        val state = KnoxPolicyState()
        assertEquals(PolicyStatus.Disabled, state.globalUninstallRestriction)
        assertEquals(PolicyStatus.Disabled, state.globalForceStopRestriction)
        assertEquals(PolicyStatus.NotApplied, state.uninstallProtection)
        assertEquals(PolicyStatus.NotApplied, state.forceStopProtection)
        assertFalse(state.isAnyPolicyActive)
    }

    @Test
    fun testAppLockProtectionActiveWhileGlobalRestrictionsRemainDisabled() {
        val state = KnoxPolicyState(
            isDeviceOwner = true,
            isKnoxAvailable = true,
            uninstallProtection = PolicyStatus.Applied,
            globalUninstallRestriction = PolicyStatus.Disabled,
            forceStopProtection = PolicyStatus.Applied,
            globalForceStopRestriction = PolicyStatus.Disabled,
            disableProtection = PolicyStatus.Applied,
            adminRemovableProtection = PolicyStatus.Applied,
            batteryProtection = PolicyStatus.Applied
        )

        // App Lock self-protection is confirmed active
        assertTrue(state.uninstallProtection.isConfirmedActive)
        assertTrue(state.forceStopProtection.isConfirmedActive)
        assertTrue(state.disableProtection.isConfirmedActive)
        assertTrue(state.isAnyPolicyActive)

        // CRITICAL: Global restrictions for all other apps must be DISABLED
        assertEquals(PolicyStatus.Disabled, state.globalUninstallRestriction)
        assertEquals(PolicyStatus.Disabled, state.globalForceStopRestriction)
        assertFalse(state.globalUninstallRestriction.isConfirmedActive)
        assertFalse(state.globalForceStopRestriction.isConfirmedActive)
    }

    @Test
    fun testGlobalRestrictionEnforcementDetection() {
        // If an erroneous global restriction is detected, it should be flagged as Applied
        val corruptedState = KnoxPolicyState(
            uninstallProtection = PolicyStatus.Applied,
            globalUninstallRestriction = PolicyStatus.Applied, // Corrupted state!
            globalForceStopRestriction = PolicyStatus.Applied  // Corrupted state!
        )

        assertTrue(corruptedState.globalUninstallRestriction.isConfirmedActive)
        assertTrue(corruptedState.globalForceStopRestriction.isConfirmedActive)
    }
}
