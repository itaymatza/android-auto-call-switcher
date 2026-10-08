package org.carcallrouter.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class SingleFlightQueryTest {
    private class Queue : Executor {
        val jobs = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            jobs.addLast(command)
        }

        fun run() = jobs.removeFirst().run()
    }

    @Test
    fun slowQueriesNeverBlockOwnerOrAccumulateWork() {
        val worker = Queue()
        val owner = Queue()
        var now = 10L
        val results = mutableListOf<Triple<Int?, Long, Long>>()
        val query =
            SingleFlightQuery<Int>(worker, owner, { now }) { result, queued, started ->
                results.add(Triple(result.getOrNull(), queued, started))
            }
        assertTrue(query.submit { 42 })
        repeat(1_000) { assertFalse(query.submit { error("must not queue") }) }
        assertEquals(1, worker.jobs.size)
        assertTrue(results.isEmpty())
        now = 25
        worker.run()
        assertFalse(query.submit { 43 })
        now = 30
        owner.run()
        assertEquals(listOf(Triple(42, 10L, 25L)), results)
        assertTrue(query.submit { 43 })
        worker.run()
        owner.run()
        assertEquals(43, results.last().first)
    }

    @Test
    fun invalidatedAndClosedCompletionsCannotPublish() {
        val worker = Queue()
        val owner = Queue()
        var publications = 0
        val query = SingleFlightQuery<Int>(worker, owner, { 0 }) { _, _, _ -> publications++ }
        query.submit { 1 }
        query.invalidate()
        assertFalse(query.submit { 2 })
        worker.run()
        owner.run()
        assertEquals(0, publications)
        query.submit { 3 }
        worker.run()
        query.close()
        owner.run()
        assertEquals(0, publications)
        assertFalse(query.submit { 4 })
    }

    @Test
    fun errorsAndExecutorRejectionReleaseTheSlot() {
        val worker = Queue()
        val owner = Queue()
        val failures = mutableListOf<Throwable>()
        val query =
            SingleFlightQuery<Int>(worker, owner, { 0 }) { result, _, _ ->
                result.exceptionOrNull()?.let(failures::add)
            }
        query.submit { throw IllegalStateException("profile unavailable") }
        worker.run()
        owner.run()
        assertTrue(failures.single() is IllegalStateException)
        assertTrue(query.submit { 1 })
        worker.run()
        owner.run()
        val rejected =
            SingleFlightQuery<Int>(Executor { throw RejectedExecutionException() }, owner, { 0 }) { result, _, _ ->
                assertTrue(result.isFailure)
                failures.add(requireNotNull(result.exceptionOrNull()))
            }
        assertTrue(rejected.submit { 2 })
        assertTrue(rejected.submit { 3 })
        assertEquals(3, failures.size)
    }
}
