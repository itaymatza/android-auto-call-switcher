package org.carcallrouter.companion.core

import java.util.concurrent.Executor

/** Optional process diagnostics never block routing, enqueue work, or serve as authorization. */
class DiagnosticSampler<T>(
    worker: Executor,
    owner: Executor,
    private val clock: () -> Long,
    private val read: () -> T,
    private val observed: (T) -> Unit,
) {
    data class Observation<T>(
        val value: T,
        val sampledAt: Long,
    )

    private var value: Observation<T>? = null
    private var refreshAt = Long.MIN_VALUE
    private val query =
        SingleFlightQuery<T>(worker, owner, clock) { result, queued, _ ->
            val now = clock()
            refreshAt = now + REFRESH_MS
            value = result.getOrNull()?.takeIf { now - queued in 0..MAX_QUERY_MS }?.let { Observation(it, queued) }
            value?.let { observed(it.value) }
        }

    fun sample(): Observation<T>? {
        val now = clock()
        if (now >= refreshAt) query.submit(read)
        return value?.takeIf { now - it.sampledAt in 0..REFRESH_MS }
    }

    companion object {
        private const val MAX_QUERY_MS = 750L
        private const val REFRESH_MS = 30_000L
    }
}
