package org.carcallrouter.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleStabilityTest {
    @Test
    fun cachedSamplesAndWallTimeCannotEstablishStability() {
        val samples = SampleStability<String>(750)
        assertFalse(samples.isStableFor(250))
        samples.observe("target", 100, 100)
        samples.observe("target", 100, 700)
        assertFalse(samples.isStableFor(250))
        samples.observe("target", 350, 700)
        assertTrue(samples.isStableFor(250))
        assertEquals("target", samples.value)
        assertEquals(100L, samples.since)
    }

    @Test
    fun unknownMissingFutureAndExpiredSamplesBreakHistory() {
        for (kind in 0..3) {
            val samples = SampleStability<String>(750)
            samples.observe("target", 100, 100)
            when (kind) {
                0 -> samples.observe(null, 200, 200)
                1 -> samples.observe("target", null, 200)
                2 -> samples.observe("target", 201, 200)
                3 -> samples.observe("target", 0, 751)
            }
            assertNull(samples.value)
            assertNull(samples.since)
            samples.observe("target", 800, 800)
            assertFalse(samples.isStableFor(250))
            samples.observe("target", 1050, 1050)
            assertTrue(samples.isStableFor(250))
        }
    }

    @Test
    fun changedOwnerBackwardsSampleAndSamplingGapStartNewHistory() {
        for (kind in 0..2) {
            val samples = SampleStability<String>(750)
            samples.observe("target", 100, 100)
            samples.observe("target", 350, 350)
            assertTrue(samples.isStableFor(250))
            when (kind) {
                0 -> samples.observe("other", 400, 400)
                1 -> samples.observe("target", 300, 400)
                2 -> samples.observe("target", 1101, 1101)
            }
            assertFalse(samples.isStableFor(250))
        }
    }
}
