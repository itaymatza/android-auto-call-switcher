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
        )

    private fun recovering(): RoutingPolicy {
        val policy = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, Route.TARGET) }
        for (time in listOf(0L, 250L)) {
            assertEquals(null, policy.evaluate(sample(time)).command)
        }
        assertEquals(RoutingPolicy.Command.Reconcile("observed-owner"), policy.evaluate(sample(500)).command)
        assertEquals(0, policy.requests)
        assertTrue(policy.hasUnfinishedRequest)
        assertEquals("observed-owner", policy.pendingRecoveryEndpointKey)
        return policy
    }

    @Test fun reconcilesBeforeTheSingleTargetRequestAvoidingSameEndpointNoOp() {
        val p = recovering()
        p.selectorRecoverySucceeded()
        assertFalse(p.evaluate(sample(600)).requestTarget) // acceptance is insufficient
        val request = p.evaluate(sample(700).copy(recoveryEndpointCurrent = true, route = Route.OTHER_BLUETOOTH))
        assertEquals(RoutingPolicy.Command.Target(1), request.command)
        p.selectorRecoveryFailed() // late reconciliation outcome cannot end the target transaction
        p.requestSucceeded(2, 701) // stale/foreign target attempt cannot complete it either
        p.requestSucceeded(1, 710)
        p.evaluate(sample(750).copy(targetHfpAudio = true, selectorRecoveryAvailable = false))
        p.evaluate(sample(1000).copy(targetHfpAudio = true, selectorRecoveryAvailable = false))
        assertTrue(p.verified)
        assertEquals(Phase.RELEASED, p.phase)
        assertEquals(1, p.requests)
        assertEquals(1, p.selectorRecoveries)
        assertFalse(p.hasUnfinishedRequest)
        assertEquals(null, p.evaluate(sample(20000)).command)
    }

    @Test fun targetFailureNeverStartsAnotherReconciliationOrRetry() {
        val p = recovering()
        assertTrue(p.evaluate(sample(600).copy(recoveryEndpointCurrent = true)).requestTarget)
        for (t in 850L..4600L step 250) assertEquals(null, p.evaluate(sample(t)).command)
        assertEquals(Phase.FAILED, p.phase)
        assertFalse(p.verified)
        assertEquals(1, p.requests)
        assertEquals(null, p.evaluate(sample(30000)).command)
    }

    @Test fun interruptionsAtEveryRecoveryPhasePreventTheTargetRequest() {
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
            assertEquals(null, p.evaluate(change(sample(600).copy(recoveryEndpointCurrent = true))).command)
            assertEquals(null, p.evaluate(sample(700).copy(recoveryEndpointCurrent = true)).command)
        }
        for (at in listOf(500L, 600L, 2999L)) {
            val p = recovering()
            assertTrue(p.observeExternalRequestAfterTarget())
            assertFalse(p.evaluate(sample(at).copy(recoveryEndpointCurrent = true)).requestTarget)
            assertEquals(ReasonCode.EXTERNAL_ENDPOINT_REQUEST, p.reasonCode)
        }
    }

    @Test fun unknownEvidenceCanWaitButCannotAuthorizeOrExtendRecovery() {
        for (projectionUnknown in listOf(false, true)) {
            val p = recovering()
            val unknown =
                sample(600).copy(
                    selectorRecoveryAvailable = null,
                    recoveryEndpointKey = null,
                    targetHfpConnected = null,
                    targetHfpSampleAt = null,
                    projection = if (projectionUnknown) null else true,
                )
            assertEquals(null, p.evaluate(unknown).command)
            assertEquals(3000L, p.evaluate(unknown.copy(now = 2900)).wakeAt)
            p.selectorRecoverySucceeded() // outcome may arrive while evidence is unknown
            assertFalse(p.evaluate(unknown.copy(now = 3000)).requestTarget)
            assertEquals(ReasonCode.SELECTOR_RECOVERY_NOT_CONFIRMED, p.reasonCode)
            assertFalse(p.evaluate(sample(3100).copy(recoveryEndpointCurrent = true)).requestTarget)
        }
    }

    @Test fun recoveryDeadlineAndSampleFreshnessMatrix() {
        var cases = 0
        for (now in listOf(500L, 501L, 750L, 2999L, 3000L, 3100L)) {
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
                            val eligible = current && ownerKnown && now < 3000 && age in 0..750 && now - age > 500
                            assertEquals("now=$now age=$age current=$current owner=$ownerKnown", eligible, decision.requestTarget)
                            assertFalse(decision.restoreSelector)
                            assertEquals(if (eligible) 1 else 0, p.requests)
                            cases++
                        }
                    }
                }
            }
        }
        assertEquals(288, cases)
    }

    @Test fun transientChangingStaleAndUnresolvedOwnersCannotAuthorizeACommand() {
        for (case in 0..5) {
            val p = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, Route.TARGET) }
            assertEquals(null, p.evaluate(sample(0)).command)
            p.evaluate(sample(250))
            val changed =
                when (case) {
                    0 -> sample(500).copy(recoveryEndpointKey = "new-owner")
                    1 -> sample(500).copy(selectorRecoveryAvailable = null)
                    2 -> sample(1000).copy(targetHfpSampleAt = 0)
                    3 -> sample(500).copy(targetHfpAudio = null)
                    4 -> sample(500).copy(recoveryEndpointKey = null)
                    else -> sample(1100) // missing observations break continuity
                }
            assertEquals(null, p.evaluate(changed).command)
            assertEquals(0, p.requests)
            assertEquals(0, p.selectorRecoveries)
        }
        val p = recovering()
        p.selectorRecoveryFailed()
        assertFalse(p.evaluate(sample(600).copy(recoveryEndpointCurrent = true)).requestTarget)
    }

    @Test fun cachedOrRegressingSamplesCannotAccumulateOwnerStability() {
        val p = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, Route.TARGET) }
        for (now in listOf(0L, 250L, 500L, 750L)) {
            assertEquals(null, p.evaluate(sample(now).copy(targetHfpSampleAt = 0)).command)
        }
        assertEquals(null, p.evaluate(sample(800).copy(targetHfpSampleAt = 400)).command)
        assertEquals(null, p.evaluate(sample(850).copy(targetHfpSampleAt = 300)).command)
        assertEquals(null, p.evaluate(sample(900).copy(targetHfpSampleAt = 750)).command)
        assertTrue(p.evaluate(sample(1000)).restoreSelector)
    }

    @Test fun unresolvedDisplayedTargetExpiresWithoutSubmittingAnIdempotentRequest() {
        val p = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, Route.TARGET) }
        for (now in 0L..10000L step 250) {
            assertEquals(null, p.evaluate(sample(now).copy(recoveryEndpointKey = null)).command)
        }
        assertEquals(ReasonCode.EVIDENCE_DEADLINE_EXPIRED, p.reasonCode)
        assertFalse(p.hasActed)
    }
}
