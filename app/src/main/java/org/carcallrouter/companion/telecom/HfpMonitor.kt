package org.carcallrouter.companion.telecom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.RouterLog
import org.carcallrouter.companion.core.SingleFlightQuery
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class HfpMonitor(
    private val context: Context,
    private val changed: () -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var sampleQuality = "UNSAMPLED"
    private var lastTrigger = "none"
    private var queryDurationMs: Long? = null

    fun diagnosticFields(): Array<Pair<String, Any?>> =
        arrayOf(
            "hfp_sample_quality" to sampleQuality,
            "hfp_query_trigger" to lastTrigger,
            "hfp_query_ms" to queryDurationMs,
            "hfp_proxy_ready" to (headset != null),
            // Public getters can return false/empty on service error without throwing.
            "hfp_negative_is_ambiguous" to true,
        )

    private data class Sample(
        val connected: Set<String>,
        val audio: Set<String>,
        val started: Long,
    )

    private val query =
        SingleFlightQuery<Sample>(
            worker = worker,
            owner = Executor { handler.post(it) },
            clock = SystemClock::elapsedRealtime,
        ) { result, queuedAt, startedAt ->
            val now = SystemClock.elapsedRealtime()
            val sample = result.getOrNull()
            // A query which itself stalled is not fresh evidence, even if it just returned.
            known = sample != null && now - sample.started <= MAX_SAMPLE_AGE_MS
            sampleQuality =
                if (sample == null) {
                    "ERROR"
                } else if (known) {
                    "OBSERVED"
                } else {
                    "STALE"
                }
            queryDurationMs = now - startedAt
            connected = if (known) requireNotNull(sample).connected else emptySet()
            audioConnected = if (known) requireNotNull(sample).audio else emptySet()
            sampledAt = sample?.started
            sampleSequence++
            RouterLog.event(
                "HFP_QUERY_TIMING",
                "queueMs=${startedAt - queuedAt}; elapsedMs=${now - startedAt}; known=$known; sample=$sampleSequence",
            )
            result.exceptionOrNull()?.let { RouterLog.event("HFP_QUERY_ERROR", it.javaClass.simpleName) }
            val stateKey = "$known|${connected.sorted()}|${audioConnected.sorted()}"
            if (stateKey != lastLoggedState) {
                lastLoggedState = stateKey
                RouterLog.event(
                    "HFP_STATE",
                    "known=$known; connected=${connected.map(RouterLog::deviceId)}; sco=${audioConnected.map(RouterLog::deviceId)}",
                )
            }
            changed()
        }
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var headset: BluetoothHeadset? = null
    private var closed = false
    private var registered = false
    private var lastLoggedState: String? = null
    var known = false
        private set
    var connected: Set<String> = emptySet()
        private set
    var audioConnected: Set<String> = emptySet()
        private set
    var sampledAt: Long? = null
        private set
    var sampleSequence: Long = 0
        private set
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                // Extras are not trusted. Re-query the authenticated Bluetooth service.
                refresh("broadcast_${intent.action?.substringAfterLast('.') ?: "unknown"}")
            }
        }
    private val listener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(
                profile: Int,
                proxy: BluetoothProfile,
            ) {
                handler.post {
                    if (closed) {
                        runCatching { adapter?.closeProfileProxy(profile, proxy) }
                    } else if (profile == BluetoothProfile.HEADSET) {
                        query.invalidate()
                        known = false
                        sampleQuality = "UNSAMPLED"
                        sampledAt = null
                        connected = emptySet()
                        audioConnected = emptySet()
                        headset = proxy as? BluetoothHeadset
                        refresh("proxy_connected")
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                handler.post {
                    if (!closed && profile == BluetoothProfile.HEADSET) {
                        query.invalidate()
                        headset = null
                        known = false
                        sampleQuality = "PROXY_DISCONNECTED"
                        connected = emptySet()
                        audioConnected = emptySet()
                        changed()
                    }
                }
            }
        }

    fun start() {
        if (!Access.bluetoothGranted(context)) {
            sampleQuality = "PERMISSION_MISSING"
            changed()
            return
        }
        try {
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
                    addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                }
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            registered = true
            if (adapter?.getProfileProxy(context, listener, BluetoothProfile.HEADSET) != true) {
                RouterLog.event("HFP_PROXY", "Bluetooth profile proxy unavailable")
            }
        } catch (e: RuntimeException) {
            RouterLog.event("HFP_ERROR", e.javaClass.simpleName)
        }
    }

    /** Queries run off the owner thread. Every accepted completion schedules evaluation. */
    fun refresh(trigger: String = "unspecified") {
        if (closed) return
        val proxy = headset
        if (proxy == null || !Access.bluetoothGranted(context)) {
            sampleQuality = if (proxy == null) "PROXY_UNAVAILABLE" else "PERMISSION_MISSING"
            known = false
            connected = emptySet()
            audioConnected = emptySet()
            sampledAt = null
            changed()
            return
        }
        val submitted =
            query.submit {
                val started = SystemClock.elapsedRealtime()
                RouterLog.event("HFP_QUERY_STARTED", "trigger=$trigger")
                val devices = proxy.connectedDevices
                RouterLog.event(
                    "HFP_QUERY_STAGE_COMPLETED",
                    "operation=connected_devices; elapsedMs=${SystemClock.elapsedRealtime() - started}",
                )
                val connected = devices.map { it.address.uppercase() }.toSet()
                val audio =
                    devices
                        .filter {
                            val deviceId = RouterLog.deviceId(it.address)
                            val queryStarted = SystemClock.elapsedRealtime()
                            RouterLog.event("HFP_QUERY_STAGE_STARTED", "operation=audio_connected; device=$deviceId")
                            val observed = proxy.isAudioConnected(it)
                            RouterLog.event(
                                "HFP_QUERY_STAGE_COMPLETED",
                                "operation=audio_connected; device=$deviceId; elapsedMs=${SystemClock.elapsedRealtime() - queryStarted}; observed=$observed",
                            )
                            observed
                        }.map { it.address.uppercase() }
                        .toSet()
                val ended = SystemClock.elapsedRealtime()
                RouterLog.event("HFP_QUERY_COMPLETED", "elapsedMs=${ended - started}; deviceCount=${devices.size}")
                Sample(connected, audio, started)
            }
        if (submitted) lastTrigger = trigger
    }

    override fun close() {
        closed = true
        query.close()
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        headset?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        headset = null
    }

    companion object {
        // Process-wide, zero-queue worker: a stuck Binder cannot leak replacement
        // threads or collect jobs from later calls/monitor instances.
        private val worker =
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
                Thread(task, "router-hfp-query").apply { isDaemon = true }
            })
        const val MAX_SAMPLE_AGE_MS = 750L
    }
}
