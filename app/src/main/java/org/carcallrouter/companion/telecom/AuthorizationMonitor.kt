package org.carcallrouter.companion.telecom

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.core.SingleFlightQuery
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Read-only protected access observation. Null is pending/stale/error, never a grant. */
class AuthorizationMonitor internal constructor(
    private val context: Context,
    queryWorker: Executor = worker,
    private val readAccess: () -> Boolean = {
        context.getSystemService(TelecomManager::class.java)?.hasManageOngoingCallsPermission() ==
            true
    },
    private val changed: () -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var value: Boolean? = null
    private var sampledAt: Long? = null
    private var refreshAt = 0L
    private var closed = false
    private var quality = "UNSAMPLED"
    private var durationMs: Long? = null
    private val query =
        SingleFlightQuery<Boolean>(queryWorker, Executor { handler.post(it) }, SystemClock::elapsedRealtime) { result, _, started ->
            val now = SystemClock.elapsedRealtime()
            refreshAt = now + 250
            durationMs = now - started
            sampledAt = null
            value =
                when {
                    !Access.runtimeGranted(context) -> {
                        quality = "PERMISSION_MISSING"
                        false
                    }
                    now - started !in 0..MAX_EVIDENCE_AGE_MS -> {
                        quality = "STALE"
                        null
                    }
                    result.isFailure -> {
                        quality = "ERROR"
                        null
                    }
                    else -> {
                        quality = "OBSERVED"
                        sampledAt = started
                        result.getOrNull()
                    }
                }
            changed()
        }

    fun sample(now: Long = SystemClock.elapsedRealtime()): Boolean? {
        if (closed) return false
        if (!Access.runtimeGranted(context)) {
            query.invalidate()
            sampledAt = null
            value = null
            refreshAt = 0
            quality = "PERMISSION_MISSING"
            return false
        }
        if (sampledAt?.let { now - it !in 0..MAX_EVIDENCE_AGE_MS } == true) {
            value = null
            quality = "STALE"
        }
        if (now >= refreshAt) query.submit(readAccess)
        return value
    }

    fun diagnosticFields(now: Long = SystemClock.elapsedRealtime()): Array<Pair<String, Any?>> =
        arrayOf(
            "authorization_quality" to quality,
            "authorization_sample_age_ms" to sampledAt?.let { (now - it).coerceAtLeast(0) },
            "authorization_query_ms" to durationMs,
        )

    override fun close() {
        closed = true
        query.close()
        value = null
        sampledAt = null
    }

    companion object {
        const val MAX_EVIDENCE_AGE_MS = 750L
        private val worker =
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
                Thread(task, "router-authorization-query").apply { isDaemon = true }
            })
    }
}
