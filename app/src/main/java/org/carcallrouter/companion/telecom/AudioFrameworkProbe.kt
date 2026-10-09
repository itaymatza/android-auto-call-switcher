package org.carcallrouter.companion.telecom

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.carcallrouter.companion.RouterLog
import org.carcallrouter.companion.core.SingleFlightQuery
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Read-only framework evidence; device inventories do not prove physical call input/output. */
internal class AudioFrameworkProbe(
    context: Context,
    modeWorker: Executor = worker,
    diagnosticWorker: Executor = inventoryWorker,
) : AutoCloseable {
    data class State(
        val mode: String,
        val communicationDevice: String,
        val quality: String = "UNSAMPLED",
        val sampledAt: Long? = null,
        val queryMs: Long? = null,
        val failedOperation: String? = null,
        val diagnosticQuality: String = "UNSAMPLED",
        val diagnosticSampledAt: Long? = null,
        val diagnosticQueryMs: Long? = null,
        val diagnosticFailedOperation: String? = null,
    )

    private data class Inventory(
        val device: String = "UNKNOWN",
        val description: String? = null,
        val failedOperation: String? = null,
    )

    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val owner = Executor { handler.post(it) }
    private var closed = false
    private var sampledAt: Long? = null
    private var cached = State("UNKNOWN", "UNKNOWN")
    private var inventoryAt: Long? = null
    private var inventoryDevice = "UNKNOWN"
    private var inventoryQuality = "UNSAMPLED"
    private var inventoryQueryMs: Long? = null
    private var inventoryFailedOperation: String? = null
    private var lastDeviceInventory: String? = null

    private val query =
        SingleFlightQuery<String>(modeWorker, owner, SystemClock::elapsedRealtime) { result, queuedAt, startedAt ->
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            sampledAt = startedAt
            cached =
                when {
                    elapsed !in 0..750 -> State("UNKNOWN", "UNKNOWN", "STALE", startedAt, elapsed)
                    result.isFailure -> State("UNKNOWN", "UNKNOWN", "ERROR", startedAt, elapsed, "mode")
                    manager == null -> State("UNKNOWN", "UNKNOWN", "UNAVAILABLE", startedAt, elapsed)
                    else -> State(result.getOrThrow(), "UNKNOWN", "OBSERVED", startedAt, elapsed)
                }
            RouterLog.event(
                "AUDIO_QUERY_COMPLETED",
                "queueMs=${startedAt - queuedAt}; elapsedMs=$elapsed; mode=${cached.mode}; " +
                    "quality=${cached.quality}; operation=${cached.failedOperation ?: "none"}",
            )
        }

    // Optional Binder reads have their own process-wide slot. Neither a stuck inventory
    // nor a communication-device getter may starve the mode evidence used by routing.
    private val inventoryQuery =
        SingleFlightQuery<Inventory>(diagnosticWorker, owner, SystemClock::elapsedRealtime) { result, queuedAt, startedAt ->
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            inventoryAt = startedAt
            inventoryQueryMs = elapsed
            inventoryFailedOperation = if (result.isFailure) "worker" else result.getOrThrow().failedOperation
            inventoryQuality =
                when {
                    elapsed !in 0..750 -> "STALE"
                    inventoryFailedOperation != null -> "ERROR"
                    manager == null -> "UNAVAILABLE"
                    else -> "OBSERVED"
                }
            inventoryDevice = if (inventoryQuality == "OBSERVED") result.getOrThrow().device else "UNKNOWN"
            RouterLog.event(
                "AUDIO_DIAGNOSTIC_QUERY_COMPLETED",
                "queueMs=${startedAt - queuedAt}; elapsedMs=$elapsed; quality=$inventoryQuality; " +
                    "operation=${inventoryFailedOperation ?: "none"}",
            )
            if (inventoryQuality == "OBSERVED") {
                val description = result.getOrThrow().description!!
                if (description != lastDeviceInventory) {
                    lastDeviceInventory = description
                    RouterLog.event("AUDIO_DEVICE_INVENTORY", description)
                }
            }
        }

    fun sample(now: Long = SystemClock.elapsedRealtime()): State {
        if (closed) return State("UNKNOWN", "UNKNOWN", "CLOSED", diagnosticQuality = "CLOSED")
        if (sampledAt?.let { now - it in 0..249 } != true) query.submit { readMode() }
        if (inventoryAt?.let { now - it in 0..249 } != true) inventoryQuery.submit { readInventory() }
        val state =
            if (sampledAt?.let { now - it in 0..750 } == true) {
                cached
            } else {
                cached.copy(mode = "UNKNOWN", quality = if (sampledAt == null) "UNSAMPLED" else "STALE")
            }
        val diagnosticFresh = inventoryAt?.let { now - it in 0..750 } == true
        return state.copy(
            communicationDevice = if (diagnosticFresh) inventoryDevice else "UNKNOWN",
            diagnosticQuality = if (diagnosticFresh || inventoryAt == null) inventoryQuality else "STALE",
            diagnosticSampledAt = inventoryAt,
            diagnosticQueryMs = inventoryQueryMs,
            diagnosticFailedOperation = inventoryFailedOperation,
        )
    }

    private fun readMode(): String =
        when (manager?.mode) {
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            null -> "UNKNOWN"
            else -> "OTHER"
        }

    private fun readInventory(): Inventory {
        var operation = "communication_device"
        return try {
            val communicationDevice = manager?.communicationDevice
            val device =
                when (communicationDevice?.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
                    null -> "UNKNOWN"
                    else -> "OTHER"
                }
            // Device IDs are framework-local. Export salted address aliases, never names.
            operation = "device_inventory"
            val inventory = manager?.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)?.map(::describeDevice)
            operation = "available_communication_devices"
            val available = manager?.availableCommunicationDevices?.map(::describeDevice)
            operation = "microphone_mute"
            val microphoneMuted = manager?.isMicrophoneMute
            Inventory(
                device,
                "communicationDevice=${describeDevice(communicationDevice)}; " +
                    "devices=$inventory; availableCommunication=$available; microphoneMuted=$microphoneMuted; " +
                    "inventoryIsNotActiveRouting=true; physicalInputVerified=false; physicalOutputVerified=false",
            )
        } catch (_: RuntimeException) {
            Inventory(failedOperation = operation)
        }
    }

    private fun describeDevice(device: AudioDeviceInfo?): String =
        if (device == null) {
            "UNKNOWN"
        } else {
            "id=${device.id},type=${device.type},source=${device.isSource},sink=${device.isSink}," +
                "address=${device.address.takeIf { it.isNotBlank() }?.let(RouterLog::deviceId) ?: "UNAVAILABLE"}"
        }

    override fun close() {
        closed = true
        query.close()
        inventoryQuery.close()
        cached = State("UNKNOWN", "UNKNOWN", "CLOSED")
        inventoryDevice = "UNKNOWN"
        lastDeviceInventory = null
    }

    companion object {
        // Process-wide, zero-queue worker: a stuck Binder cannot leak replacement
        // threads or collect jobs from later calls/monitor instances.
        private val worker = boundedWorker("router-audio-mode")
        private val inventoryWorker = boundedWorker("router-audio-inventory")

        private fun boundedWorker(name: String): Executor =
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
                Thread(task, name).apply { isDaemon = true }
            })
    }
}
