package org.carcallrouter.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedSerialExecutorTest {
    @Test fun stalledWriterHasBoundedMemoryAndNeverExecutesRejectedWorkOnCaller() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val observed = Collections.synchronizedList(mutableListOf<Int>())
        val caller = Thread.currentThread()
        BoundedSerialExecutor(2, "test-diagnostic-writer").use { writer ->
            try {
                assertTrue(
                    writer.submit {
                        assertTrue(Thread.currentThread() !== caller)
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        observed.add(0)
                    },
                )
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertTrue(writer.submit { observed.add(1) })
                assertTrue(
                    writer.submit {
                        observed.add(2)
                        drained.countDown()
                    },
                )
                repeat(1000) { assertFalse(writer.submit { observed.add(99) }) }
                assertEquals(emptyList<Int>(), observed.toList())
                release.countDown()
                assertTrue(drained.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(0, 1, 2), observed.toList())
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun closedWriterRejectsNewWorkWithoutRunningIt() {
        val writer = BoundedSerialExecutor(1, "closed-writer")
        writer.close()
        assertFalse(writer.submit { error("must not run") })
    }
}
