package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.RoutingPolicy.Phase
import org.carcallrouter.companion.core.RoutingPolicy.ReasonCode
import org.carcallrouter.companion.core.RoutingPolicy.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingReconciliationTest {
    private fun sample(now: Long) =
        RoutingPolicy.Snapshot(
            now,
            true,
            true,
            true,
            true,
            true,
            false,
            true,
            true,
            false,
            now,
            true,
            true,
            1,
            Route.TARGET,
            "observed-owner",
            false,
            true,
        )

    private fun recovering(): RoutingPolicy {
        val policy = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, Route.TARGET) }
        assertTrue(policy.evaluate(sample(0)).requestTarget)
        policy.requestSucceeded(1, 2) // Android's same-endpoint success without an audio change.
        for (time in 250L..3750L step 250) {
            assertFalse(policy.evaluate(sample(time)).requestTarget)
        }
        assertTrue(policy.evaluate(sample(4000)).restoreSelector)
        assertEquals("observed-owner", policy.pendingRecoveryEndpointKey)
        return policy
    }

    @Test fun sameEndpointNoOpCanRecoverWithoutAnUnboundedRetry() {
        val p = recovering()
        p.selectorRecoverySucceeded()
        assertFalse(p.evaluate(sample(4100)).requestTarget) // acceptance is insufficient
        val retry = p.evaluate(sample(4200).copy(recoveryEndpointCurrent = true, route = Route.OTHER_BLUETOOTH))
        assertTrue(retry.requestTarget)
        assertEquals(2, retry.requestAttempt)
        p.requestSucceeded(1, 4201) // delayed old outcome must not complete attempt 2
        p.requestSucceeded(2, 4210)
        p.evaluate(sample(4250).copy(targetHfpAudio = true, selectorRecoveryAvailable = false))
        p.evaluate(sample(4500).copy(targetHfpAudio = true, selectorRecoveryAvailable = false))
        assertTrue(p.verified)
        assertEquals(Phase.RELEASED, p.phase)
        assertEquals(2, p.requests)
        assertEquals(1, p.selectorRecoveries)
        assertFalse(p.evaluate(sample(20000)).requestTarget)
    }

    @Test fun recoveryCannotLoopWhenTheSecondTargetRequestAlsoFails() {
        val p = recovering()
        assertTrue(p.evaluate(sample(4100).copy(recoveryEndpointCurrent = true)).requestTarget)
        for (t in 4350L..8100L step 250) {
            val d = p.evaluate(sample(t))
            assertFalse(d.requestTarget)
            assertFalse(d.restoreSelector)
        }
        assertEquals(Phase.FAILED, p.phase)
        assertFalse(p.verified)
        assertEquals(2, p.requests)
    }

    @Test fun interruptionsAtEveryRecoveryPhasePreventTheTargetRetry() {
        val changes: List<(RoutingPolicy.Snapshot) -> RoutingPolicy.Snapshot> =
            listOf(
                { it.copy(authorized = false) },
                { it.copy(authorized = null) },
                { it.copy(enabled = false) },
                { it.copy(active = false) },
                { it.copy(singleCall = false) },
                { it.copy(safeCellularCall = false) },
                { it.copy(callSafetyPending = true) },
                { it.copy(projection = false) },
                { it.copy(targetHfpConnected = false) },
                { it.copy(targetAvailable = false) },
                { it.copy(recoveryEndpointKey = "different-owner") },
                { it.copy(selectorRecoveryAvailable = false) },
            )
        for (change in changes) {
            val p = recovering()
            val d = p.evaluate(change(sample(4100).copy(recoveryEndpointCurrent = true)))
            assertFalse(d.requestTarget)
            assertFalse(p.evaluate(sample(4200).copy(recoveryEndpointCurrent = true)).requestTarget)
        }
        for (at in listOf(4000L, 4100L, 5499L)) {
            val p = recovering()
            assertTrue(p.observeExternalRequestAfterTarget())
            assertFalse(p.evaluate(sample(at).copy(recoveryEndpointCurrent = true)).requestTarget)
            assertEquals(ReasonCode.EXTERNAL_ENDPOINT_REQUEST, p.reasonCode)
        }
    }

    @Test fun unknownEvidenceCanWaitButCannotAuthorizeOrExtendRecovery() {
        val p = recovering()
        assertFalse(
            p
                .evaluate(
                    sample(4100).copy(
                        selectorRecoveryAvailable = null,
                        recoveryEndpointKey = null,
                        targetHfpConnected = null,
                        targetHfpSampleAt = null,
                    ),
                ).requestTarget,
        )
        assertFalse(p.evaluate(sample(5500).copy(recoveryEndpointCurrent = true)).requestTarget)
        assertEquals(Phase.FAILED, p.phase)
        assertFalse(p.evaluate(sample(5600).copy(recoveryEndpointCurrent = true)).requestTarget)
    }

    @Test fun recoveryDeadlineAndSampleFreshnessMatrix() {
        var cases = 0
        for (now in listOf(4000L, 4001L, 4250L, 5499L, 5500L, 5600L)) {
            for (age in listOf(-1L, 0L, 250L, 750L, 751L, 1500L)) {
                for (current in listOf(false, true)) {
                    for (ownerKnown in listOf(false, true)) {
                        for (accepted in listOf(false, true)) {
                            val p = recovering()
                            if (accepted) p.selectorRecoverySucceeded()
                            val decision =
                                p.evaluate(
                                    sample(now).copy(
                                        targetHfpSampleAt = now - age,
                                        recoveryEndpointCurrent = current,
                                        selectorRecoveryAvailable = if (ownerKnown) true else null,
                                    ),
                                )
                            val eligible = current && ownerKnown && now < 5500 && age in 0..750 && now - age >= 4000
                            assertEquals("now=$now age=$age current=$current owner=$ownerKnown", eligible, decision.requestTarget)
                            assertFalse(decision.restoreSelector)
                            assertEquals(if (eligible) 2 else 1, p.requests)
                            cases++
                        }
                    }
                }
            }
        }
        assertEquals(288, cases)
    }

    @Test fun transientOrChangingOwnersCannotAuthorizeReconciliation() {
        for (case in 0..4) {
            val p = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, Route.TARGET) }
            p.evaluate(sample(0))
            for (t in 250L..3750L step 250) p.evaluate(sample(t))
            val final =
                when (case) {
                    0 -> sample(4000).copy(recoveryEndpointKey = "new-owner")
                    1 -> sample(4000).copy(selectorRecoveryAvailable = null)
                    2 -> sample(4000).copy(targetHfpSampleAt = 0)
                    3 -> sample(4000).copy(targetHfpAudio = null)
                    else -> sample(4000).copy(recoveryEndpointKey = null)
                }
            assertFalse(p.evaluate(final).restoreSelector)
        }
        val p = recovering()
        p.selectorRecoveryFailed()
        assertFalse(p.evaluate(sample(4100).copy(recoveryEndpointCurrent = true)).requestTarget)
    }
}
