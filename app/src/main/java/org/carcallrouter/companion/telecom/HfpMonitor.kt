package org.carcallrouter.companion.telecom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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

/** Owner-thread state; exact SCO reads cannot be starved by optional labels or power diagnostics. */
@SuppressLint("MissingPermission")
class HfpMonitor internal constructor(
    private val context: Context,
    queryWorker: Executor = worker,
    metadataWorker: Executor = labelWorker,
    diagnosticWorker: Executor = powerWorker,
    private val changed: () -> Unit,
) : AutoCloseable {
    private data class Sample(
        val devices: List<BluetoothDevice>,
        val connected: Set<String>,
        val audio: Set<String>,
    )

    private data class Metadata(
        val labels: Map<String, Set<String>>,
    )

    private data class Observation<T>(
        val generation: Long,
        val result: Result<T>,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val owner = Executor { handler.post(it) }
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var headset: BluetoothHeadset? = null
    private var closed = false
    private var registered = false
    private var generation = 0L
    private var sampleQuality = "UNSAMPLED"
    private var lastTrigger = "none"
    private var queryDurationMs: Long? = null
    private var observed = false
    private var sampledDevices = emptyList<BluetoothDevice>()
    private var sampledConnected = emptySet<String>()
    private var sampledAudio = emptySet<String>()
    private var labels = emptyMap<String, Set<String>>()
    private var labelsAt: Long? = null
    private var labelsQuality = "UNSAMPLED"
    private var labelsQueryMs: Long? = null
    private var lastLoggedState: String? = null

    var sampledAt: Long? = null
        private set
    var sampleSequence = 0L
        private set

    val known: Boolean
        get() = !closed && observed && Access.bluetoothGranted(context) && fresh(sampledAt)
    val connected: Set<String> get() = if (known) sampledConnected else emptySet()
    val audioConnected: Set<String> get() = if (known) sampledAudio else emptySet()

    /** Local identity only: neither names nor aliases enter exported diagnostics. */
    val deviceLabels: Map<String, Set<String>>
        get() = if (known && fresh(labelsAt) && labels.keys == sampledConnected) labels else emptyMap()

    val labelsKnown: Boolean
        get() = known && deviceLabels.keys == sampledConnected && deviceLabels.values.all { it.isNotEmpty() } && fresh(labelsAt)

    fun diagnosticFields(): Array<Pair<String, Any?>> =
        arrayOf(
            "hfp_sample_quality" to
                if (closed) {
                    "CLOSED"
                } else if (observed && !fresh(sampledAt)) {
                    "STALE"
                } else {
                    sampleQuality
                },
            "hfp_query_trigger" to lastTrigger,
            "hfp_query_ms" to queryDurationMs,
            "hfp_proxy_ready" to (headset != null),
            "hfp_generation" to generation,
            "hfp_labels_known" to labelsKnown,
            "hfp_labels_quality" to if (labelsAt != null && !fresh(labelsAt)) "STALE" else labelsQuality,
            "hfp_labels_age_ms" to labelsAt?.let { SystemClock.elapsedRealtime() - it },
            "hfp_labels_query_ms" to labelsQueryMs,
            // Public getters can return false/empty on service error without throwing.
            "hfp_negative_is_ambiguous" to true,
        )

    private val query =
        SingleFlightQuery<Observation<Sample>>(queryWorker, owner, SystemClock::elapsedRealtime) { result, queuedAt, started ->
            val observation = result.getOrNull()
            if (observation != null && observation.generation != generation) {
                RouterLog.event("HFP_RESULT_IGNORED", "reason=stale_generation")
                refresh("event_recheck")
                return@SingleFlightQuery
            }
            val now = SystemClock.elapsedRealtime()
            val sample = observation?.result?.getOrNull()
            if (!Access.bluetoothGranted(context)) {
                invalidateEvidence("PERMISSION_MISSING")
            } else {
                observed = sample != null && now - started in 0..MAX_SAMPLE_AGE_MS
                sampleQuality =
                    if (sample == null) {
                        "ERROR"
                    } else if (observed) {
                        "OBSERVED"
                    } else {
                        "STALE"
                    }
                sampledAt = started
                // Membership changes without a broadcast also invalidate identity metadata.
                if (sample?.connected != sampledConnected) clearLabels()
                sampledDevices = if (observed) requireNotNull(sample).devices else emptyList()
                sampledConnected = if (observed) requireNotNull(sample).connected else emptySet()
                sampledAudio = if (observed) requireNotNull(sample).audio else emptySet()
            }
            queryDurationMs = now - started
            sampleSequence++
            RouterLog.event(
                "HFP_QUERY_TIMING",
                "queueMs=${started - queuedAt}; elapsedMs=$queryDurationMs; known=$known; sample=$sampleSequence",
            )
            (result.exceptionOrNull() ?: observation?.result?.exceptionOrNull())?.let {
                RouterLog.event("HFP_QUERY_ERROR", it.javaClass.simpleName)
            }
            val stateKey = "$known|${connected.sorted()}|${audioConnected.sorted()}"
            if (stateKey != lastLoggedState) {
                lastLoggedState = stateKey
                RouterLog.event(
                    "HFP_STATE",
                    "known=$known; connected=${connected.map(RouterLog::deviceId)}; sco=${audioConnected.map(RouterLog::deviceId)}",
                )
            }
            refreshLabels()
            refreshPower()
            changed()
        }

    private val metadataQuery =
        SingleFlightQuery<Observation<Metadata>>(metadataWorker, owner, SystemClock::elapsedRealtime) { result, _, started ->
            val observation = result.getOrNull()
            if (observation != null && observation.generation != generation) {
                refreshLabels()
                return@SingleFlightQuery
            }
            if (!Access.bluetoothGranted(context)) {
                invalidateEvidence("PERMISSION_MISSING")
                changed()
                return@SingleFlightQuery
            }
            val sample = observation?.result?.getOrNull()
            val elapsed = SystemClock.elapsedRealtime() - started
            labelsAt = started
            labelsQueryMs = elapsed
            labelsQuality =
                when {
                    sample == null -> "ERROR"
                    elapsed !in 0..MAX_SAMPLE_AGE_MS -> "STALE"
                    !known || sample.labels.keys != sampledConnected -> "SUPERSEDED"
                    else -> "OBSERVED"
                }
            labels = if (labelsQuality == "OBSERVED") requireNotNull(sample).labels else emptyMap()
            RouterLog.event("HFP_LABEL_QUERY_COMPLETED", "quality=$labelsQuality; elapsedMs=$elapsed; identitiesComplete=$labelsKnown")
            if (labelsQuality == "SUPERSEDED") refreshLabels()
            changed()
        }

    // Pure power diagnostics must not block either exact SCO or name-based identity.
    private val powerQuery =
        SingleFlightQuery<Observation<Int?>>(diagnosticWorker, owner, SystemClock::elapsedRealtime) { result, _, started ->
            val observation = result.getOrNull()
            if (observation != null && observation.generation != generation) {
                refreshPower()
                return@SingleFlightQuery
            }
            if (!Access.bluetoothGranted(context)) {
                invalidateEvidence("PERMISSION_MISSING")
                changed()
                return@SingleFlightQuery
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val state = observation?.result?.getOrNull()
            val quality =
                if (elapsed !in 0..MAX_SAMPLE_AGE_MS) {
                    "STALE"
                } else if (state == null) {
                    "UNKNOWN"
                } else {
                    "OBSERVED"
                }
            RouterLog.event(
                "BLUETOOTH_ADAPTER_SNAPSHOT",
                "state=${if (quality == "OBSERVED") state else "UNKNOWN"}; quality=$quality; elapsedMs=$elapsed; " +
                    "sampledAt=$started; profilePolicy=UNAVAILABLE_PUBLIC_API; connectionOrder=NOT_OBSERVED",
            )
        }

    private fun refreshPower() {
        if (!known) return
        val epoch = generation
        powerQuery.submit { Observation(epoch, runCatching { adapter?.state }) }
    }

    private fun stage(
        epoch: Long,
        kind: String,
        message: String,
    ) {
        owner.execute { if (!closed && epoch == generation) RouterLog.event(kind, message) }
    }

    private fun refreshLabels() {
        if (!known) return
        val epoch = generation
        val devices = sampledDevices.toList()
        metadataQuery.submit {
            Observation(
                epoch,
                runCatching {
                    stage(epoch, "HFP_QUERY_STAGE_STARTED", "operation=device_labels")
                    val labels =
                        devices.associate { device ->
                            device.address.uppercase() to setOfNotNull(device.name, device.alias).filter { it.isNotBlank() }.toSet()
                        }
                    stage(epoch, "HFP_QUERY_STAGE_COMPLETED", "operation=device_labels")
                    Metadata(labels)
                },
            )
        }
    }

    private fun fresh(at: Long?): Boolean = at?.let { SystemClock.elapsedRealtime() - it in 0..MAX_SAMPLE_AGE_MS } == true

    private fun clearLabels() {
        labels = emptyMap()
        labelsAt = null
        labelsQuality = "UNSAMPLED"
        labelsQueryMs = null
    }

    private fun invalidateEvidence(quality: String) {
        generation++
        observed = false
        sampleQuality = quality
        sampledAt = null
        sampledDevices = emptyList()
        sampledConnected = emptySet()
        sampledAudio = emptySet()
        clearLabels()
        labelsQuality = quality
    }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (closed) return
                // Extras cannot authorize routing. Discard both cached and in-flight evidence.
                invalidateEvidence("EVENT_RECHECK")
                changed()
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
                        headset?.takeIf { it !== proxy }?.let { old -> runCatching { adapter?.closeProfileProxy(profile, old) } }
                        invalidateEvidence("UNSAMPLED")
                        headset = proxy as? BluetoothHeadset
                        changed()
                        refresh("proxy_connected")
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                handler.post {
                    if (!closed && profile == BluetoothProfile.HEADSET) {
                        headset = null
                        invalidateEvidence("PROXY_DISCONNECTED")
                        changed()
                    }
                }
            }
        }

    fun start() {
        if (closed || registered) return
        if (!Access.bluetoothGranted(context)) {
            invalidateEvidence("PERMISSION_MISSING")
            changed()
            return
        }
        try {
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
                    addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                    addAction(BluetoothDevice.ACTION_ALIAS_CHANGED)
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

    /** Periodic refresh retains fresh evidence; external events invalidate it immediately. */
    fun refresh(trigger: String = "unspecified") {
        if (closed) return
        val proxy = headset
        if (proxy == null || !Access.bluetoothGranted(context)) {
            invalidateEvidence(if (proxy == null) "PROXY_UNAVAILABLE" else "PERMISSION_MISSING")
            changed()
            return
        }
        val epoch = generation
        val submitted =
            query.submit {
                Observation(
                    epoch,
                    runCatching {
                        stage(epoch, "HFP_QUERY_STARTED", "trigger=$trigger")
                        stage(epoch, "HFP_QUERY_STAGE_STARTED", "operation=connected_devices")
                        val devices = proxy.connectedDevices.toList()
                        stage(epoch, "HFP_QUERY_STAGE_COMPLETED", "operation=connected_devices")
                        val connected = devices.map { it.address.uppercase() }.toSet()
                        val audio =
                            devices
                                .filter { device ->
                                    val at = SystemClock.elapsedRealtime()
                                    val alias = RouterLog.deviceId(device.address)
                                    stage(epoch, "HFP_QUERY_STAGE_STARTED", "operation=audio_connected; device=$alias")
                                    val active = proxy.isAudioConnected(device)
                                    stage(
                                        epoch,
                                        "HFP_QUERY_STAGE_COMPLETED",
                                        "operation=audio_connected; device=$alias; elapsedMs=${SystemClock.elapsedRealtime() - at}; observed=$active",
                                    )
                                    active
                                }.map { it.address.uppercase() }
                                .toSet()
                        Sample(devices, connected, audio)
                    },
                )
            }
        if (submitted) lastTrigger = trigger
    }

    override fun close() {
        if (closed) return
        closed = true
        invalidateEvidence("CLOSED")
        query.close()
        metadataQuery.close()
        powerQuery.close()
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        headset?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        headset = null
    }

    companion object {
        const val MAX_SAMPLE_AGE_MS = 750L
        private val worker = boundedWorker("router-hfp-query")
        private val labelWorker = boundedWorker("router-hfp-labels")
        private val powerWorker = boundedWorker("router-bluetooth-power")

        private fun boundedWorker(name: String): Executor =
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
                Thread(task, name).apply { isDaemon = true }
            })
    }
}
