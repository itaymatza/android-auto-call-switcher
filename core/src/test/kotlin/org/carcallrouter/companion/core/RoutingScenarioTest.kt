package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.RoutingPolicy.Phase
import org.carcallrouter.companion.core.RoutingPolicy.Route
import org.carcallrouter.companion.core.RoutingPolicy.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingScenarioTest {
    @Test
    fun slowScoConfirmationIsTheStrongestRecordedEvidence() {
        var now = 0L
        val lines = mutableListOf<String>()
        val trace = RoutingTrace({ now }, { "slow-sco" }, lines::add)
        trace.begin("automatic", "active")
        trace.confirmTelecom()
        now = 3_000
        trace.confirmTargetHfpAudio()
        assertEquals(
            RoutingTrace.Confirmation.TARGET_HFP_AUDIO,
            trace.finish(Phase.RELEASED, RoutingPolicy.ReasonCode.TARGET_AUDIO_CONFIRMED, "complete"),
        )
    }

    @Test
    fun capturedSamsungSplitBrainForcesExactlyOneBmwRequest() {
        val policy = RoutingPolicy(settleDelayMs = 500).also { it.begin(0, Route.TARGET) }

        assertFalse(
            policy.evaluate(snapshot(7, route = Route.TARGET, targetHfpAudio = false)).requestTarget,
        )
        policy.observeRoute(Route.OTHER_BLUETOOTH, 7)
        val request = policy.evaluate(snapshot(500, route = Route.TARGET, targetHfpAudio = false))

        assertTrue(request.requestTarget)
        assertEquals(1, request.requestAttempt)
        assertEquals(1, policy.requests)
        assertFalse(policy.evaluate(snapshot(3_000, route = Route.TARGET, targetHfpAudio = false)).requestTarget)
    }

    @Test
    fun targetScoAfterRequestMustRemainStableBeforeRelease() {
        val policy = RoutingPolicy(settleDelayMs = 0, targetAudioStableMs = 250).also { it.begin(0, Route.TARGET) }
        policy.evaluate(snapshot(0, route = Route.TARGET, targetHfpAudio = false))
        policy.evaluate(snapshot(300, route = Route.TARGET, targetHfpAudio = true))
        assertEquals(Phase.STABILIZING, policy.phase)
        policy.evaluate(snapshot(549, route = Route.TARGET, targetHfpAudio = true))
        assertFalse(policy.verified)
        policy.evaluate(snapshot(550, route = Route.TARGET, targetHfpAudio = true))
        assertTrue(policy.verified)
        assertEquals(Phase.RELEASED, policy.phase)
    }

    @Test
    fun latePrerequisitesStillReceiveOneFullActionWindow() {
        val policy = RoutingPolicy(settleDelayMs = 500).also { it.begin(0, Route.COMPETING_DEVICE) }
        assertFalse(policy.evaluate(snapshot(500, targetAvailable = false)).requestTarget)
        assertTrue(policy.evaluate(snapshot(9_000, targetAvailable = true)).requestTarget)
        assertFalse(policy.evaluate(snapshot(11_500, targetAvailable = true)).requestTarget)
        policy.evaluate(snapshot(13_000, targetAvailable = true))
        assertEquals(Phase.FAILED, policy.phase)
    }

    @Test
    fun manualSelectionAfterCompletionCannotBeReasserted() {
        val policy = RoutingPolicy(settleDelayMs = 0, targetAudioStableMs = 0).also { it.begin(0, Route.COMPETING_DEVICE) }
        policy.evaluate(snapshot(0, targetHfpAudio = false))
        policy.evaluate(snapshot(100, route = Route.TARGET, targetHfpAudio = true))
        assertEquals(Phase.RELEASED, policy.phase)

        policy.observeRoute(Route.SPEAKER, 1_000)
        assertFalse(policy.evaluate(snapshot(1_000, route = Route.SPEAKER, targetHfpAudio = false)).requestTarget)
        assertEquals(1, policy.requests)
    }

    @Test
    fun startupActivityWaitsForQuietButCannotExtendPastCap() {
        val policy = RoutingPolicy().also { it.begin(0, Route.TARGET) }
        policy.observeStartupActivity(280)
        assertFalse(policy.evaluate(snapshot(300)).requestTarget)
        policy.observeStartupActivity(620)
        assertFalse(policy.evaluate(snapshot(899)).requestTarget)
        assertTrue(policy.evaluate(snapshot(900)).requestTarget)
        assertEquals(1, policy.requests)
    }

    @Test
    fun shorterCallbackTimeoutDoesNotCutOffLateAudio() {
        val policy = RoutingPolicy().also { it.begin(0, Route.TARGET) }
        assertTrue(policy.evaluate(snapshot(300)).requestTarget)
        assertFalse(policy.evaluate(snapshot(1_800)).requestTarget)
        assertEquals(RoutingPolicy.ReasonCode.REQUEST_TIMED_OUT, policy.reasonCode)
        policy.evaluate(snapshot(2_000, targetHfpAudio = true))
        assertFalse(policy.verified)
        policy.evaluate(snapshot(2_250, targetHfpAudio = true))
        assertTrue(policy.verified)
        assertEquals(1, policy.requests)
    }

    @Test
    fun otherServiceRequestStopsSelectorRecoveryButNotAudioConfirmation() {
        val policy = RoutingPolicy().also { it.begin(0, Route.TARGET) }
        assertFalse(policy.observeExternalRequestAfterTarget())
        policy.evaluate(snapshot(300, route = Route.TARGET))
        assertTrue(policy.observeExternalRequestAfterTarget())
        policy.evaluate(snapshot(4_300, route = Route.TARGET, selectorRecoveryAvailable = true))
        assertEquals(RoutingPolicy.ReasonCode.EXTERNAL_ENDPOINT_REQUEST, policy.reasonCode)
        assertEquals(0, policy.selectorRecoveries)

        val audio = RoutingPolicy().also { it.begin(0, Route.TARGET) }
        audio.evaluate(snapshot(300, route = Route.TARGET))
        audio.observeExternalRequestAfterTarget()
        audio.evaluate(snapshot(700, route = Route.TARGET, targetHfpAudio = true))
        audio.evaluate(snapshot(950, route = Route.TARGET, targetHfpAudio = true))
        assertTrue(audio.verified)
        assertFalse(audio.observeExternalRequestAfterTarget())
    }

    @Test
    fun quietPeriodIgnoresManualAndLateEvents() {
        val manual = RoutingPolicy().also { it.begin(1_000, Route.TARGET, manualOneShot = true) }
        manual.observeStartupActivity(1_100)
        assertTrue(manual.evaluate(snapshot(1_100)).requestTarget)

        val automatic = RoutingPolicy().also { it.begin(1_000, Route.TARGET) }
        automatic.observeStartupActivity(999)
        assertTrue(automatic.evaluate(snapshot(1_300)).requestTarget)
        automatic.observeStartupActivity(1_350)
        assertEquals(1, automatic.requests)

        val idle = RoutingPolicy()
        idle.observeStartupActivity(10)
        assertEquals(Phase.IDLE, idle.phase)
    }

    private fun snapshot(
        now: Long,
        projection: Boolean? = true,
        targetHfpConnected: Boolean? = true,
        targetHfpAudio: Boolean? = false,
        selectorRecoveryAvailable: Boolean? = false,
        targetAvailable: Boolean? = true,
        route: Route = Route.COMPETING_DEVICE,
    ) = Snapshot(
        now = now,
        enabled = true,
        authorized = true,
        active = true,
        singleCall = true,
        safeCellularCall = true,
        projection = projection,
        targetHfpConnected = targetHfpConnected,
        targetHfpAudio = targetHfpAudio,
        targetHfpSampleAt = now,
        selectorRecoveryAvailable = selectorRecoveryAvailable,
        targetAvailable = targetAvailable,
        endpointRevision = 1,
        route = route,
    )
}
