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

import android.content.AsyncQueryHandler
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference

/** Main-thread owned, event-driven projection observation using the AndroidX host protocol. */
class ProjectionMonitor(
    context: Context,
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
    private val query = ProjectionQueryHandler(app.contentResolver, this)
    private val refresh =
        Runnable {
            if (open && !queryPending) {
                try {
                    queryPending = true
                    lastQueryAt = SystemClock.elapsedRealtime()
                    RouterLog.event("PROJECTION_QUERY_STARTED", "generation=${generation + 1}")
                    query.cancelOperation(QUERY_TOKEN)
                    query.startQuery(QUERY_TOKEN, ++generation, HOST_URI, arrayOf(STATE_COLUMN), null, null, null)
                } catch (e: RuntimeException) {
                    queryPending = false
                    RouterLog.event("PROJECTION_ERROR", e.javaClass.simpleName)
                    changed(null)
                }
            }
        }
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                // Broadcast extras cannot authorize a route; always re-query the provider.
                if (intent.action == UPDATE_ACTION && open) {
                    // Stop new automatic requests until the changed connection state is re-verified.
                    if (queryPending) generation++
                    changed(null)
                    main.removeCallbacks(refresh)
                    main.post(refresh)
                }
            }
        }

    private fun onQueryComplete(
        cookie: Any?,
        cursor: Cursor?,
    ) {
        val value: Boolean? =
            try {
                cursor?.use {
                    val column = it.getColumnIndex(STATE_COLUMN)
                    if (column < 0 || !it.moveToFirst()) {
                        null
                    } else {
                        when (it.getInt(column)) {
                            0, 1 -> false
                            2 -> true
                            else -> null
                        }
                    }
                }
            } catch (e: RuntimeException) {
                RouterLog.event("PROJECTION_ERROR", e.javaClass.simpleName)
                null
            }
        if (open) queryPending = false
        if (open && cookie != generation) {
            main.post(refresh)
            return
        }
        if (open && cookie == generation) {
            RouterLog.event(
                "PROJECTION_QUERY_COMPLETED",
                "active=$value; elapsedMs=${SystemClock.elapsedRealtime() - lastQueryAt}; generation=$generation",
            )
            val state = value?.toString() ?: "unknown"
            if (state != lastLoggedState) {
                lastLoggedState = state
                RouterLog.event("PROJECTION", "active=$value; source=AndroidX_host_provider")
            }
            changed(value)
        }
    }

    /** Bounded service startup recheck. Never infer projection from Bluetooth connectivity. */
    fun requestRefresh() {
        if (open && !queryPending && (lastQueryAt == Long.MIN_VALUE || SystemClock.elapsedRealtime() - lastQueryAt >= 500)) {
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
        query.cancelOperation(QUERY_TOKEN)
        if (registered) {
            runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }
    }

    private class ProjectionQueryHandler(
        resolver: ContentResolver,
        monitor: ProjectionMonitor,
    ) : AsyncQueryHandler(resolver) {
        private val monitor = WeakReference(monitor)

        override fun onQueryComplete(
            token: Int,
            cookie: Any?,
            cursor: Cursor?,
        ) {
            val owner = monitor.get()
            if (owner == null) {
                cursor?.close()
            } else {
                owner.onQueryComplete(cookie, cursor)
            }
        }
    }

    companion object {
        private const val QUERY_TOKEN = 42
        private const val STATE_COLUMN = "CarConnectionState"
        private const val UPDATE_ACTION = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
        private val HOST_URI = Uri.parse("content://androidx.car.app.connection")
    }
}
