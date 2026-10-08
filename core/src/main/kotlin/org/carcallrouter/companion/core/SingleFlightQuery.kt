package org.carcallrouter.companion.core

import java.util.concurrent.Executor

/** Owner-thread coordinator: a slow query occupies one slot, never an unbounded work queue. */
class SingleFlightQuery<T>(
    private val worker: Executor,
    private val owner: Executor,
    private val clock: () -> Long,
    private val completed: (Result<T>, Long, Long) -> Unit,
) : AutoCloseable {
    private var pending = false
    private var closed = false
    private var generation = 0L

    fun submit(query: () -> T): Boolean {
        if (closed || pending) return false
        pending = true
        val epoch = generation
        val queuedAt = clock()
        try {
            worker.execute {
                val startedAt = clock()
                val result =
                    try {
                        Result.success(query())
                    } catch (error: RuntimeException) {
                        Result.failure(error)
                    }
                owner.execute {
                    pending = false
                    if (!closed && epoch == generation) completed(result, queuedAt, startedAt)
                }
            }
        } catch (error: RuntimeException) {
            pending = false
            if (!closed && epoch == generation) completed(Result.failure(error), queuedAt, queuedAt)
        }
        return true
    }

    /** Retains the occupied slot until completion; invalidation cannot queue behind a stuck query. */
    fun invalidate() {
        generation++
    }

    override fun close() {
        closed = true
        invalidate()
    }
}
