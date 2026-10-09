package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.RoutingPolicy.Phase
import org.carcallrouter.companion.core.RoutingPolicy.Route
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingFreshnessTest {
    private fun snapshot(
        now: Long,
        sampledAt: Long?,
        audio: Boolean? = true,
        projection: Boolean? = true,
    ) = RoutingPolicy.Snapshot(now, true, true, true, true, true, false, projection, true, audio, sampledAt, false, true, 1, Route.TARGET)

    @Test
    fun cachedPositiveSampleCannotConfirmByPassageOfTime() {
        val p = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, Route.TARGET) }
        p.evaluate(snapshot(0, 0))
        p.evaluate(snapshot(250, 0))
        assertFalse(p.verified)
        p.evaluate(snapshot(250, 250))
        assertTrue(p.verified)
    }

    @Test
    fun missingFutureStaleAndWidelySeparatedEvidenceCannotConfirm() {
        val p = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, Route.TARGET) }
        p.evaluate(snapshot(0, null))
        p.evaluate(snapshot(0, 1))
        assertFalse(p.verified)
        p.evaluate(snapshot(10, 10))
        p.evaluate(snapshot(1_000, 10))
        assertFalse(p.verified)
        p.evaluate(snapshot(1_100, 1_100))
        assertFalse(p.verified)
        p.evaluate(snapshot(1_350, 1_350))
        assertTrue(p.verified)
        val gap = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, Route.TARGET) }
        gap.evaluate(snapshot(0, 0))
        gap.evaluate(snapshot(1_000, 1_000))
        assertFalse(gap.verified)
        gap.evaluate(snapshot(1_250, 1_250))
        assertTrue(gap.verified)
        val backwards = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, Route.TARGET) }
        backwards.evaluate(snapshot(100, 100))
        backwards.evaluate(snapshot(150, 50))
        assertFalse(backwards.verified)
    }

    @Test
    fun initialDisconnectedProjectionCanRecoverOnlyBeforeFirstAction() {
        val p = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, Route.TARGET) }
        assertFalse(p.evaluate(snapshot(0, 0, audio = false, projection = false)).requestTarget)
        assertTrue(p.phase == Phase.WAITING)
        assertTrue(p.evaluate(snapshot(500, 500, audio = false)).requestTarget)
        p.evaluate(snapshot(600, 600, audio = false, projection = false))
        assertTrue(p.phase == Phase.SUSPENDED)
        assertFalse(p.evaluate(snapshot(700, 700, audio = false)).requestTarget)
        val expired = RoutingPolicy(settleDelayMs = 0).also { it.begin(0, Route.TARGET) }
        expired.evaluate(snapshot(10_000, 10_000, projection = false))
        assertTrue(expired.phase == Phase.FAILED)
    }
}
