package org.carcallrouter.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSafetyTest {
    private fun snapshot(
        now: Long,
        pending: Boolean,
        safe: Boolean = !pending,
    ) = RoutingPolicy.Snapshot(
        now,
        true,
        true,
        true,
        true,
        safe,
        pending,
        true,
        true,
        false,
        now,
        false,
        true,
        1,
        RoutingPolicy.Route.COMPETING_DEVICE,
    )

    @Test fun pendingWaitsWithoutRequestThenResumesOnFreshEvidence() {
        val policy = RoutingPolicy()
        policy.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
        val waiting = policy.evaluate(snapshot(300, true))
        assertFalse(waiting.requestTarget)
        assertEquals(RoutingPolicy.ReasonCode.WAITING_CALL_SAFETY, policy.reasonCode)
        assertTrue(requireNotNull(waiting.wakeAt) <= 550)
        assertTrue(policy.evaluate(snapshot(600, false)).requestTarget)
    }

    @Test fun pendingEvenWithConflictingSafeFlagCannotRequest() {
        val policy = RoutingPolicy()
        policy.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
        assertFalse(policy.evaluate(snapshot(300, true, true)).requestTarget)
    }

    @Test fun pendingCannotWaitBeyondEvidenceDeadline() {
        val policy = RoutingPolicy()
        policy.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
        policy.evaluate(snapshot(30_000, true))
        assertEquals(RoutingPolicy.Phase.FAILED, policy.phase)
        assertEquals(0, policy.requests)
    }

    @Test fun lostClassificationAfterARequestCannotAuthorizeAnother() {
        val policy = RoutingPolicy()
        policy.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
        assertTrue(policy.evaluate(snapshot(300, false)).requestTarget)
        assertFalse(policy.evaluate(snapshot(600, true)).requestTarget)
        assertEquals(RoutingPolicy.Phase.SUSPENDED, policy.phase)
        assertEquals(1, policy.requests)
    }

    @Test fun endedOrMultipleCallAndDisabledAutomationCannotWaitForSafety() {
        for (change in listOf<(RoutingPolicy.Snapshot) -> RoutingPolicy.Snapshot>(
            { it.copy(active = false) },
            { it.copy(singleCall = false) },
            { it.copy(enabled = false) },
            { it.copy(authorized = false) },
        )) {
            val policy = RoutingPolicy()
            policy.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
            assertFalse(policy.evaluate(change(snapshot(300, true))).requestTarget)
            assertEquals(RoutingPolicy.Phase.SUSPENDED, policy.phase)
        }
    }

    @Test fun unknownAuthorizationWaitsWithoutRequestThenResumes() {
        val policy = RoutingPolicy()
        policy.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
        assertFalse(policy.evaluate(snapshot(300, false).copy(authorized = null)).requestTarget)
        assertEquals(RoutingPolicy.ReasonCode.AUTHORIZATION_UNKNOWN, policy.reasonCode)
        assertTrue(policy.evaluate(snapshot(600, false)).requestTarget)
    }

    @Test fun unknownAuthorizationTimesOutAndNeverOverridesAnUnsafeCall() {
        val policy = RoutingPolicy()
        policy.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
        policy.evaluate(snapshot(300, false, false).copy(authorized = null))
        assertEquals(RoutingPolicy.ReasonCode.UNSAFE_CALL, policy.reasonCode)
        val waiting = RoutingPolicy()
        waiting.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
        waiting.evaluate(snapshot(30_000, false).copy(authorized = null))
        assertEquals(RoutingPolicy.Phase.FAILED, waiting.phase)
    }

    @Test fun expiredAuthorizationAfterRequestAndInactiveStatesNeverWait() {
        val policy = RoutingPolicy()
        policy.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
        policy.evaluate(snapshot(300, false))
        assertFalse(policy.evaluate(snapshot(600, false).copy(authorized = null)).requestTarget)
        assertEquals(RoutingPolicy.Phase.SUSPENDED, policy.phase)
        for (changed in listOf<(RoutingPolicy.Snapshot) -> RoutingPolicy.Snapshot>(
            { it.copy(singleCall = false) },
            { it.copy(active = false) },
            { it.copy(enabled = false) },
        )) {
            val idle = RoutingPolicy()
            idle.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
            idle.evaluate(changed(snapshot(300, false).copy(authorized = null)))
            assertEquals(RoutingPolicy.Phase.SUSPENDED, idle.phase)
        }
    }
}
