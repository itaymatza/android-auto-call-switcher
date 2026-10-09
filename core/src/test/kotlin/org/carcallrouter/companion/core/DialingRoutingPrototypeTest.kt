package org.carcallrouter.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Candidate adapter simulation, not production DIALING integration or device qualification. */
class DialingRoutingPrototypeTest {
    private enum class State { DIALING, ACTIVE, DISCONNECTED }

    private class Prototype {
        val policy = RoutingPolicy()
        var state = State.DIALING
        var audio = false
        var route = RoutingPolicy.Route.COMPETING_DEVICE
        var authorized = true
        var safe = true
        var single = true
        var projection: Boolean? = true
        var connected: Boolean? = true
        var endpoint: Boolean? = true

        init {
            // Experimental translation: treat outgoing DIALING as eligible for the existing
            // transaction. No production call state or routing gate is changed by these tests.
            policy.begin(0, route)
        }

        fun tick(now: Long): RoutingPolicy.Decision =
            policy.evaluate(
                RoutingPolicy.Snapshot(
                    now = now,
                    enabled = true,
                    authorized = authorized,
                    active = state != State.DISCONNECTED,
                    singleCall = single,
                    safeCellularCall = safe,
                    projection = projection,
                    targetHfpConnected = connected,
                    targetHfpAudio = audio,
                    targetHfpSampleAt = now,
                    selectorRecoveryAvailable = false,
                    targetAvailable = endpoint,
                    endpointRevision = 1,
                    route = route,
                ),
            )
    }

    @Test
    fun audioCanBeConfirmedBeforeAnswerWithoutAnotherRequestAtAnswer() {
        val sim = Prototype()
        assertTrue(sim.tick(300).requestTarget)
        sim.audio = true
        sim.route = RoutingPolicy.Route.TARGET
        sim.tick(500)
        sim.tick(750)
        assertTrue(sim.policy.verified)
        sim.state = State.ACTIVE
        assertFalse(sim.tick(2_000).requestTarget)
        assertEquals(1, sim.policy.requests)
    }

    @Test
    fun immediateAnswerStillHasPhysicalSetupLatency() {
        val sim = Prototype()
        sim.state = State.ACTIVE
        assertFalse(sim.tick(50).requestTarget)
        assertFalse(sim.policy.verified)
        assertTrue(sim.tick(300).requestTarget)
        sim.audio = true
        sim.tick(800)
        sim.tick(1_050)
        assertTrue(sim.policy.verified)
    }

    @Test
    fun shortCallEndingBeforeSettlingMakesNoRequest() {
        val sim = Prototype()
        assertFalse(sim.tick(100).requestTarget)
        sim.state = State.DISCONNECTED
        assertFalse(sim.tick(150).requestTarget)
        assertEquals(0, sim.policy.requests)
    }

    @Test
    fun longDialingWithScoUnavailableUntilAnswerExposesPrematureDeadline() {
        val sim = Prototype()
        assertTrue(sim.tick(300).requestTarget)
        sim.tick(4_300)
        assertEquals(RoutingPolicy.Phase.FAILED, sim.policy.phase)
        sim.state = State.ACTIVE
        sim.audio = true
        sim.tick(8_000)
        assertFalse(sim.policy.verified)
        assertEquals(1, sim.policy.requests)
    }

    @Test
    fun answerTimeTakeoverAfterDialingConfirmationIsNotRecoveredByReleasedPolicy() {
        val sim = Prototype()
        sim.tick(300)
        sim.audio = true
        sim.tick(500)
        sim.tick(750)
        assertTrue(sim.policy.verified)
        sim.state = State.ACTIVE
        sim.audio = false
        sim.route = RoutingPolicy.Route.COMPETING_DEVICE
        assertFalse(sim.tick(2_000).requestTarget)
        assertEquals(RoutingPolicy.Phase.RELEASED, sim.policy.phase)
        // Historical verification is not evidence of current audio ownership.
        assertTrue(sim.policy.verified)
        assertFalse(sim.audio)
    }

    @Test
    fun missingPrerequisitesPreventDialingRequest() {
        for (configure in listOf<(Prototype) -> Unit>(
            { it.authorized = false },
            { it.safe = false },
            { it.single = false },
            { it.projection = false },
            { it.connected = false },
            { it.endpoint = false },
        )) {
            val sim = Prototype()
            configure(sim)
            assertFalse(sim.tick(300).requestTarget)
            assertEquals(0, sim.policy.requests)
        }
    }

    @Test
    fun anotherRequestCancelsDialingTransactionWithoutFighting() {
        val sim = Prototype()
        sim.tick(300)
        sim.policy.requestFailed(1, RoutingPolicy.RequestError.CANCELLED_BY_OTHER)
        sim.state = State.ACTIVE
        assertFalse(sim.tick(600).requestTarget)
        assertEquals(RoutingPolicy.Phase.SUSPENDED, sim.policy.phase)
        assertEquals(1, sim.policy.requests)
    }
}
