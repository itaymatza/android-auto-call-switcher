/*
 * Copyright 2021 The Android Open Source Project
 * Modifications 2026: lifecycle-scoped Kotlin adapter, cursor closing and fail-closed errors.
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Protocol adapted from AndroidX CarConnection / CarConnectionTypeLiveData.
 * No Bluetooth-name, Wi-Fi or process-running inference is used.
 */
package org.carcallrouter.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.carcallrouter.companion.core.SingleFlightQuery
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Main-thread owned, event-driven projection observation using the AndroidX host protocol. */
class ProjectionMonitor internal constructor(
    context: Context,
    queryWorker: Executor = worker,
    private val changed: (Boolean?) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var open = false
    private var registered = false
    private var generation = 0
    private var queryPending = false
    private var lastQueryAt = Long.MIN_VALUE
    private var lastLoggedState = "uninitialized"
    private var queryTrigger = "startup"
    private var rawState: Int? = null
    private var status = "UNKNOWN"
    private var completedAt: Long? = null
    private var queryDurationMs: Long? = null

    /** Read-only provider evidence; does not turn connection age into a new routing gate. */
    fun diagnosticFields(now: Long = SystemClock.elapsedRealtime()): Array<Pair<String, Any?>> =
        arrayOf(
            "projection_status" to status,
            "projection_raw_state" to rawState,
            "projection_generation" to generation,
            "projection_query_pending" to queryPending,
            "projection_trigger" to queryTrigger,
            "projection_sample_age_ms" to completedAt?.let { (now - it).coerceAtLeast(0) },
            "projection_query_ms" to queryDurationMs,
        )

    private data class Observation(
        val raw: Int?,
        val status: String,
        val active: Boolean?,
        val generation: Int = -1,
    )

    private val query =
        SingleFlightQuery<Observation>(queryWorker, Executor { main.post(it) }, SystemClock::elapsedRealtime) { result, _, started ->
            queryPending = false
            if (open) {
                if (result.getOrNull()?.generation?.let { it != generation } == true) {
                    RouterLog.event("PROJECTION_RESULT_IGNORED", "reason=stale_generation")
                    main.post(refresh)
                    return@SingleFlightQuery
                }
                val now = SystemClock.elapsedRealtime()
                queryDurationMs = now - started
                val observation =
                    if (now - started !in 0..MAX_QUERY_AGE_MS) {
                        Observation(null, "STALE", null)
                    } else {
                        result.getOrDefault(Observation(null, "ERROR", null))
                    }
                rawState = observation.raw
                status = observation.status
                completedAt = now
                RouterLog.event(
                    "PROJECTION_QUERY_COMPLETED",
                    "active=${observation.active}; rawState=$rawState; status=$status; elapsedMs=$queryDurationMs; generation=$generation; trigger=$queryTrigger",
                )
                val state = observation.active?.toString() ?: "unknown"
                if (state != lastLoggedState) {
                    lastLoggedState = state
                    RouterLog.event("PROJECTION", "active=$state; source=AndroidX_host_provider")
                }
                changed(observation.active)
            }
        }
    private val refresh: Runnable =
        Runnable {
            if (open) {
                val epoch = generation
                val previousStatus = status
                val previousPending = queryPending
                status = "QUERYING"
                queryPending = true
                val submitted =
                    query.submit {
                        readObservation().copy(generation = epoch)
                    }
                if (submitted) {
                    lastQueryAt = SystemClock.elapsedRealtime()
                    RouterLog.event("PROJECTION_QUERY_STARTED", "generation=$generation; trigger=$queryTrigger")
                } else {
                    status = previousStatus
                    queryPending = previousPending
                }
            }
        }

    private fun readObservation(): Observation =
        try {
            // Query, window access and cursor close all stay off the service owner.
            app.contentResolver.query(HOST_URI, arrayOf(STATE_COLUMN), null, null, null)?.use {
                val column = it.getColumnIndex(STATE_COLUMN)
                if (column < 0 || !it.moveToFirst()) {
                    Observation(null, "UNKNOWN", null)
                } else {
                    val raw = it.getInt(column)
                    when (raw) {
                        0, 1 -> Observation(raw, "DISCONNECTED", false)
                        2 -> Observation(raw, "CONNECTED", true)
                        else -> Observation(raw, "UNRECOGNIZED", null)
                    }
                }
            } ?: Observation(null, "UNKNOWN", null)
        } catch (_: RuntimeException) {
            Observation(null, "ERROR", null)
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                // Broadcast extras cannot authorize a route; always re-query the provider.
                if (intent.action == UPDATE_ACTION && open) {
                    queryTrigger = "provider_broadcast"
                    // Stop new automatic requests until the changed connection state is re-verified.
                    generation++
                    status = "UNKNOWN"
                    changed(null)
                    main.removeCallbacks(refresh)
                    main.post(refresh)
                }
            }
        }

    /** Bounded service startup recheck. Never infer projection from Bluetooth connectivity. */
    fun requestRefresh() {
        if (open && (lastQueryAt == Long.MIN_VALUE || SystemClock.elapsedRealtime() - lastQueryAt >= 500)) {
            queryTrigger = "startup_recheck"
            main.removeCallbacks(refresh)
            main.post(refresh)
        }
    }

    fun start() {
        if (open) return
        open = true
        try {
            app.registerReceiver(receiver, IntentFilter(UPDATE_ACTION), Context.RECEIVER_EXPORTED)
            registered = true
            main.post(refresh)
        } catch (e: RuntimeException) {
            RouterLog.event("PROJECTION_ERROR", e.javaClass.simpleName)
            changed(null)
        }
    }

    override fun close() {
        open = false
        generation++
        main.removeCallbacks(refresh)
        query.close()
        if (registered) {
            runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }
    }

    companion object {
        private const val MAX_QUERY_AGE_MS = 750L
        private val worker =
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
                Thread(task, "router-projection-query").apply { isDaemon = true }
            })
        private const val STATE_COLUMN = "CarConnectionState"
        private const val UPDATE_ACTION = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
        private val HOST_URI = Uri.parse("content://androidx.car.app.connection")
    }
}
