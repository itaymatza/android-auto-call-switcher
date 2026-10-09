package org.carcallrouter.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerVerificationTest {
    private fun snapshot(
        now: Long,
        audio: Boolean = false,
    ) = RoutingPolicy.Snapshot(
        now = now,
        enabled = true,
        authorized = true,
        active = true,
        singleCall = true,
        safeCellularCall = true,
        projection = true,
        targetHfpConnected = true,
        targetHfpAudio = audio,
        targetHfpSampleAt = now,
        selectorRecoveryAvailable = false,
        targetAvailable = true,
        endpointRevision = 1,
        route = RoutingPolicy.Route.COMPETING_DEVICE,
    )

    @Test
    fun answerExtendsPendingVerificationWithoutSubmittingAgain() {
        val p = RoutingPolicy()
        p.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
        assertTrue(p.evaluate(snapshot(300)).requestTarget)
        p.answer(4000, RoutingPolicy.Route.COMPETING_DEVICE)
        p.evaluate(snapshot(5000, true))
        p.evaluate(snapshot(5300, true))
        assertTrue(p.verified)
        assertEquals(1, p.requests)
    }

    @Test
    fun longDialingTimeoutGetsAnAnswerWindow() {
        val p = RoutingPolicy()
        p.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
        p.evaluate(snapshot(300))
        p.evaluate(snapshot(4300))
        assertEquals(RoutingPolicy.Phase.FAILED, p.phase)
        p.answer(20_000, RoutingPolicy.Route.COMPETING_DEVICE)
        assertTrue(p.evaluate(snapshot(20_300)).requestTarget)
        p.evaluate(snapshot(20_500, true))
        p.evaluate(snapshot(20_800, true))
        assertTrue(p.verified)
    }

    @Test
    fun answerNeverRevivesAnExplicitSuspension() {
        val p = RoutingPolicy()
        p.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
        p.suspend("user", RoutingPolicy.ReasonCode.USER_OVERRIDE)
        p.answer(2000, RoutingPolicy.Route.COMPETING_DEVICE)
        assertFalse(p.evaluate(snapshot(3000)).requestTarget)
        assertEquals(RoutingPolicy.ReasonCode.USER_OVERRIDE, p.reasonCode)
    }

    @Test
    fun confirmedDialingAudioIsRevalidatedAtAnswer() {
        val p = RoutingPolicy()
        p.begin(0, RoutingPolicy.Route.COMPETING_DEVICE)
        p.evaluate(snapshot(300, true))
        p.evaluate(snapshot(600, true))
        assertTrue(p.verified)
        p.answer(2000, RoutingPolicy.Route.COMPETING_DEVICE)
        assertFalse(p.verified)
        assertTrue(p.evaluate(snapshot(2300)).requestTarget)
    }
}
