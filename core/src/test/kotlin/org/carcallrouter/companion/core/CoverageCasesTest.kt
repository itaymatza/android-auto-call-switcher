package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.EndpointIdentity.Candidate
import org.carcallrouter.companion.core.EndpointIdentity.Resolution
import org.carcallrouter.companion.core.RoutingPolicy.Phase
import org.carcallrouter.companion.core.RoutingPolicy.ReasonCode
import org.carcallrouter.companion.core.RoutingPolicy.RequestError
import org.carcallrouter.companion.core.RoutingPolicy.Route
import org.carcallrouter.companion.core.RoutingPolicy.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit edge cases keep the coverage gate tied to meaningful behavior. */
class CoverageCasesTest {
    @Test
    fun idleAndTerminalCallbacksAreNoOps() {
        val idle = RoutingPolicy()
        idle.observeRoute(Route.SPEAKER, 0)
        idle.requestSucceeded(1, 0)
        idle.requestFailed(1, RequestError.TIMEOUT)
        assertEquals(Phase.IDLE, idle.phase)

        val policy = policy()
        val attempt = requireNotNull(policy.evaluate(snapshot(0)).requestAttempt)
        policy.suspend("stop")
        policy.requestSucceeded(attempt, 20)
        policy.requestFailed(attempt, RequestError.TIMEOUT)
        assertEquals(Phase.SUSPENDED, policy.phase)
    }

    @Test
    fun stableAudioAtActionDeadlineWinsOverTimeout() {
        val policy = policy(actionWindowMs = 1_000, stableMs = 0)
        policy.evaluate(snapshot(0))
        policy.evaluate(snapshot(1_000, targetHfpAudio = true, route = Route.TARGET))
        assertEquals(Phase.RELEASED, policy.phase)
        assertEquals(ReasonCode.TARGET_AUDIO_CONFIRMED, policy.reasonCode)
    }

    @Test
    fun acceptedCallbackClearsPlatformTimeoutButNotActionDeadline() {
        val policy = policy(actionWindowMs = 1_000)
        val attempt = requireNotNull(policy.evaluate(snapshot(0)).requestAttempt)
        policy.requestSucceeded(attempt, 100)
        val decision = policy.evaluate(snapshot(500))
        assertEquals(1_000L, decision.wakeAt)
        policy.evaluate(snapshot(1_000))
        assertEquals(Phase.FAILED, policy.phase)
    }

    @Test
    fun traceLifecycleHandlesInactiveDuplicateAndSanitizedInput() {
        var now = 100L
        var session = "   "
        val lines = mutableListOf<String>()
        val trace = RoutingTrace({ now }, { session }, lines::add)
        trace.event("ignored")
        assertNull(trace.finish(Phase.FAILED, ReasonCode.REQUEST_FAILED, "ignored"))
        assertFalse(trace.isActive())

        trace.begin("auto mode", "x".repeat(120))
        trace.begin("ignored", "ignored")
        assertTrue(trace.isActive())
        now = 90
        trace.event("odd event", "nullable field" to null)
        trace.event("REQUEST_SUBMITTED")
        trace.event("ENDPOINT_CHANGED")
        trace.event("ENDPOINT_REQUEST_OBSERVED", "classification" to "SELF")
        trace.event("ENDPOINT_REQUEST_OBSERVED", "classification" to "STARTUP_REPLAY")
        trace.event("ENDPOINT_REQUEST_OBSERVED", "classification" to "EXTERNAL")
        trace.event("ENDPOINT_REQUEST_OBSERVED", "classification" to null)
        trace.event("SESSION_SUSPENDED")
        trace.confirmTargetHfpAudio()
        trace.confirmTelecom()
        trace.confirmTargetHfpAudio()
        assertEquals(
            RoutingTrace.Confirmation.TARGET_HFP_AUDIO,
            trace.finish(Phase.RELEASED, ReasonCode.TARGET_AUDIO_CONFIRMED, "done", "final_route" to Route.TARGET),
        )
        assertTrue(lines.first().contains("session=empty"))
        assertTrue(lines.any { "elapsed_ms=0" in it && "nullable_field=null" in it })
        assertEquals(1, lines.count { "TELECOM_ENDPOINT_CONFIRMED" in it })
        assertEquals(1, lines.count { "TARGET_HFP_AUDIO_CONFIRMED" in it })

        session = "second"
        trace.begin("manual", "again")
        assertEquals(
            RoutingTrace.Confirmation.NONE,
            trace.finish(Phase.FAILED, ReasonCode.REQUEST_FAILED, "done"),
        )
    }

    @Test
    fun emptySavedLabelUsesOnlyUnambiguousHfpTopology() {
        val one = Candidate("one", "Car")
        assertTrue(EndpointIdentity.resolve("   ", listOf(one), true, 1) is Resolution.Matched)
        assertTrue(
            EndpointIdentity.resolve("", listOf(one, Candidate("two", "Other")), true, 2)
                is Resolution.Unavailable,
        )
    }

    private fun policy(
        actionWindowMs: Long = 4_000,
        stableMs: Long = 250,
    ) = RoutingPolicy(
        settleDelayMs = 0,
        actionWindowMs = actionWindowMs,
        targetAudioStableMs = stableMs,
    ).also { it.begin(0, Route.COMPETING_DEVICE) }

    private fun snapshot(
        now: Long,
        targetHfpAudio: Boolean? = false,
        route: Route = Route.COMPETING_DEVICE,
    ) = Snapshot(
        now = now,
        enabled = true,
        authorized = true,
        active = true,
        singleCall = true,
        safeCellularCall = true,
        projection = true,
        targetHfpConnected = true,
        targetHfpAudio = targetHfpAudio,
        targetHfpSampleAt = now,
        selectorRecoveryAvailable = false,
        targetAvailable = true,
        endpointRevision = 1,
        route = route,
    )
}
