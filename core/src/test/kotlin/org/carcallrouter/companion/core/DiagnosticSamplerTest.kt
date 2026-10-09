package org.carcallrouter.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class DiagnosticSamplerTest {
    @Test fun stuckReadNeverBlocksOrQueuesAndLateResultIsDropped() {
        var now = 0L
        var observed = 0
        val work = ArrayDeque<Runnable>()
        val sampler = DiagnosticSampler(Executor { work.add(it) }, Executor { it.run() }, { now }, { "environment" }) { observed++ }
        repeat(10) { assertNull(sampler.sample()) }
        assertEquals(1, work.size)
        now = 1000
        work.removeFirst().run()
        assertEquals(0, observed)
        assertNull(sampler.sample())
        now = 31_000
        assertNull(sampler.sample())
        work.removeFirst().run()
        assertEquals("environment", sampler.sample()?.value)
        assertEquals(1, observed)
        now = 61_001
        assertNull(sampler.sample())
    }

    @Test fun failuresAndSaturationDoNotEscapeOrFloodWorkers() {
        for (worker in listOf(Executor { it.run() }, Executor { throw RejectedExecutionException() })) {
            var observed = 0
            val sampler = DiagnosticSampler<String>(worker, Executor { it.run() }, { 0L }, { error("unavailable") }) { observed++ }
            repeat(10) { assertNull(sampler.sample()) }
            assertEquals(0, observed)
        }
    }
}
