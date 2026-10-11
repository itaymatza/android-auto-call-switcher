package org.carcallrouter.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTraceFieldsTest {
    @Test
    fun boundaryFieldsRemainUniqueAndEnvelopeCannotBeOverridden() {
        val lines = mutableListOf<String>()
        val trace =
            RoutingTrace(
                { 100L },
                { "session" },
                lines::add,
                { arrayOf("call" to 1, "physical_audio_verified" to false, "custom key" to "context") },
            )
        trace.begin("automatic", "call_added")
        trace.event(
            "CALL_STATE_CHANGED",
            "call" to 1,
            "physical_audio_verified" to false,
            "custom_key" to "event",
            "schema" to 99,
            "session" to "wrong",
            "seq" to -1,
            "elapsed_ms" to -1,
            "event" to "wrong",
        )
        val fields = lines.last().split(" ").map { it.split("=", limit = 2) }
        assertEquals(fields.size, fields.map { it[0] }.toSet().size)
        val values = fields.associate { it[0] to it[1] }
        assertEquals("1", values["schema"])
        assertEquals("session", values["session"])
        assertEquals("2", values["seq"])
        assertEquals("0", values["elapsed_ms"])
        assertEquals("CALL_STATE_CHANGED", values["event"])
        assertEquals("event", values["custom_key"])
        assertTrue(values.containsKey("call"))
    }
}
