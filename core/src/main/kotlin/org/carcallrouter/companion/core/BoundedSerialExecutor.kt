package org.carcallrouter.companion.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Ordered background work with a fixed queue; saturation never runs work on the caller. */
class BoundedSerialExecutor(
    capacity: Int,
    threadName: String,
) : AutoCloseable {
    private val worker =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(capacity),
            { task -> Thread(task, threadName).apply { isDaemon = true } },
        )

    /** False means the work was neither run nor queued; the caller must account for its loss. */
    fun submit(task: () -> Unit): Boolean =
        try {
            worker.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }

    override fun close() {
        worker.shutdownNow()
    }
}
