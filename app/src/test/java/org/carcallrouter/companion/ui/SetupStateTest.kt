package org.carcallrouter.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStateTest {
    @Test fun reportsFirstIncompleteStep() {
        assertEquals(
            SetupPhase.NEEDS_RUNTIME_PERMISSIONS,
            SetupState(false, false, false, false).phase,
        )
        assertEquals(
            SetupPhase.NEEDS_TARGET_DEVICE,
            SetupState(true, false, false, false).phase,
        )
        assertEquals(
            SetupPhase.NEEDS_TELECOM_AUTHORIZATION,
            SetupState(true, false, true, false).phase,
        )
    }

    @Test fun distinguishesReadyFromActive() {
        val ready = SetupState(true, true, true, false)
        val active = SetupState(true, true, true, true)

        assertTrue(ready.ready)
        assertEquals(3, ready.completedSteps)
        assertEquals(SetupPhase.READY, ready.phase)
        assertTrue(active.ready)
        assertEquals(SetupPhase.ACTIVE, active.phase)
    }

    @Test fun neverReportsReadyWithMissingRequirement() {
        val state = SetupState(true, false, true, true)

        assertFalse(state.ready)
        assertEquals(2, state.completedSteps)
        assertEquals(SetupPhase.NEEDS_TELECOM_AUTHORIZATION, state.phase)
    }

    @Test fun allSixteenInputCombinationsPreserveSetupPrecedence() {
        var combinations = 0
        for (runtime in listOf(false, true)) {
            for (telecom in listOf(false, true)) {
                for (target in listOf(false, true)) {
                    for (enabled in listOf(false, true)) {
                        val state = SetupState(runtime, telecom, target, enabled)
                        assertEquals(listOf(runtime, telecom, target).count { it }, state.completedSteps)
                        assertEquals(runtime && telecom && target, state.ready)
                        val expected =
                            when {
                                !runtime -> SetupPhase.NEEDS_RUNTIME_PERMISSIONS
                                !target -> SetupPhase.NEEDS_TARGET_DEVICE
                                !telecom -> SetupPhase.NEEDS_TELECOM_AUTHORIZATION
                                enabled -> SetupPhase.ACTIVE
                                else -> SetupPhase.READY
                            }
                        assertEquals(expected, state.phase)
                        combinations++
                    }
                }
            }
        }
        assertEquals(16, combinations)
    }

    @Test fun observedUnsupportedTransportBlocksReadyAndActiveWithoutLosingSetup() {
        for (enabled in listOf(false, true)) {
            val state = SetupState(true, true, true, enabled, unsupportedCallTransport = true)
            assertFalse(state.ready)
            assertEquals(3, state.completedSteps)
            assertEquals(SetupPhase.UNSUPPORTED_CALL_TRANSPORT, state.phase)
        }
        assertEquals(SetupPhase.NEEDS_RUNTIME_PERMISSIONS, SetupState(false, true, true, true, true).phase)
        assertEquals(SetupPhase.NEEDS_TARGET_DEVICE, SetupState(true, true, false, true, true).phase)
        assertEquals(SetupPhase.UNSUPPORTED_CALL_TRANSPORT, SetupState(true, false, true, true, true).phase)
    }
}
