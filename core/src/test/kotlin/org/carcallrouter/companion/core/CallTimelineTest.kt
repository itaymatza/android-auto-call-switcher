package org.carcallrouter.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallTimelineTest {
    @Test
    fun dialingAndConnectedDurationStopAtDisconnectNotRemoval() {
        val t = CallTimeline(100, "DIALING")
        t.observe("ACTIVE", 1100)
        t.observe("HOLDING", 2100)
        t.observe("ACTIVE", 3100)
        t.observe("DISCONNECTING", 4100)
        t.observe("DISCONNECTED", 4200)
        t.observe("REMOVED", 9000)
        val f = t.fields(9000).toMap()
        assertEquals(1000L, f["dialing_duration_ms"])
        assertEquals(3000L, f["connected_duration_ms"])
        assertEquals(4000L, f["call_observed_ms"])
        assertEquals("OBSERVED_CALLBACKS", f["timing_origin"])
    }

    @Test
    fun unansweredCallHasNoConnectedDuration() {
        val t = CallTimeline(0, "DIALING")
        t.observe("DISCONNECTED", 2000)
        assertEquals(2000L, t.fields(9000).toMap()["dialing_duration_ms"])
        assertNull(t.fields(9000).toMap()["connected_duration_ms"])
        assertNull(t.fields(9000).toMap()["since_answer_ms"])
    }

    @Test
    fun incomingCallDoesNotInventDialing() {
        val t = CallTimeline(0, "RINGING")
        t.observe("ACTIVE", 1000)
        assertNull(t.fields(2000).toMap()["since_dialing_ms"])
        assertEquals(1000L, t.fields(2000).toMap()["since_answer_ms"])
    }

    @Test
    fun lateBindAndClockAnomalyAreExplicit() {
        val t = CallTimeline(5000, "ACTIVE")
        val f = t.fields(4900).toMap()
        assertEquals("LATE_BIND_OBSERVATION", f["timing_origin"])
        assertEquals(0L, f["connected_duration_ms"])
        assertEquals(false, f["physical_audio_verified"])
    }

    @Test
    fun connectingThenDialingKeepsItsOwnOrigin() {
        val t = CallTimeline(0, "CONNECTING")
        t.observe("DIALING", 100)
        t.observe("DIALING", 200)
        assertEquals(200L, t.fields(300).toMap()["since_dialing_ms"])
        assertEquals("DIALING", t.fields(300).toMap()["call_state"])
    }
}
