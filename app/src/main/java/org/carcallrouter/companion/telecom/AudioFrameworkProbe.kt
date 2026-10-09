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
) : AutoCloseable {
    data class State(
        val mode: String,
        val communicationDevice: String,
        val quality: String = "UNSAMPLED",
        val sampledAt: Long? = null,
        val queryMs: Long? = null,
        val failedOperation: String? = null,
    )

    private val manager = context.getSystemService(AudioManager::class.java)
    private var sampledAt = Long.MIN_VALUE
    private var cached = State("UNKNOWN", "UNKNOWN")
    private var lastDeviceInventory: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val query =
        SingleFlightQuery<State>(worker, Executor { handler.post(it) }, SystemClock::elapsedRealtime) { result, queuedAt, startedAt ->
            val now = SystemClock.elapsedRealtime()
            sampledAt = startedAt
            cached =
                if (now - startedAt <= 750) {
                    result.getOrDefault(State("UNKNOWN", "UNKNOWN", "ERROR", startedAt))
                } else {
                    State("UNKNOWN", "UNKNOWN", "STALE", startedAt, now - startedAt)
                }
            RouterLog.event(
                "AUDIO_QUERY_COMPLETED",
                "queueMs=${startedAt - queuedAt}; elapsedMs=${now - startedAt}; mode=${cached.mode}; " +
                    "device=${cached.communicationDevice}; quality=${cached.quality}; operation=${cached.failedOperation ?: "none"}",
            )
        }

    fun sample(now: Long = SystemClock.elapsedRealtime()): State {
        if (sampledAt == Long.MIN_VALUE || now - sampledAt >= 250) {
            query.submit { readState() }
        }
        return if (sampledAt != Long.MIN_VALUE && now - sampledAt in 0..750) {
            cached
        } else {
            cached.copy(mode = "UNKNOWN", communicationDevice = "UNKNOWN", quality = if (cached.sampledAt == null) "UNSAMPLED" else "STALE")
        }
    }

    private fun readState(): State {
        val startedAt = SystemClock.elapsedRealtime()
        var operation = "mode"
        var operationStartedAt = startedAt
        RouterLog.event("AUDIO_QUERY_STARTED", "operation=mode")
        return runCatching {
            val mode =
                manager?.mode?.let { audioMode ->
                    when (audioMode) {
                        AudioManager.MODE_IN_CALL -> "IN_CALL"
                        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                        AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
                        AudioManager.MODE_NORMAL -> "NORMAL"
                        AudioManager.MODE_RINGTONE -> "RINGTONE"
                        else -> "OTHER"
                    }
                } ?: "UNKNOWN"
            RouterLog.event(
                "AUDIO_QUERY_STAGE_COMPLETED",
                "operation=mode; elapsedMs=${SystemClock.elapsedRealtime() - operationStartedAt}",
            )
            operation = "communication_device"
            operationStartedAt = SystemClock.elapsedRealtime()
            RouterLog.event("AUDIO_QUERY_STAGE", "operation=communication_device")
            val communicationDevice = manager?.communicationDevice
            RouterLog.event(
                "AUDIO_QUERY_STAGE_COMPLETED",
                "operation=communication_device; elapsedMs=${SystemClock.elapsedRealtime() - operationStartedAt}",
            )
            val device =
                communicationDevice?.type?.let { communicationType ->
                    when (communicationType) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
                        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
                        else -> "OTHER"
                    }
                } ?: "UNKNOWN"
            // Device IDs are framework-local. Addresses may be empty or redacted by the OS;
            // only salted aliases are exported, and product names are deliberately omitted.
            operation = "device_inventory"
            operationStartedAt = SystemClock.elapsedRealtime()
            val inventory =
                manager?.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)?.map(::describeDevice)
            val available = manager?.availableCommunicationDevices?.map(::describeDevice)
            RouterLog.event(
                "AUDIO_QUERY_STAGE_COMPLETED",
                "operation=device_inventory; elapsedMs=${SystemClock.elapsedRealtime() - operationStartedAt}",
            )
            operation = "microphone_mute"
            operationStartedAt = SystemClock.elapsedRealtime()
            val microphoneMuted = manager?.isMicrophoneMute
            val deviceInventory =
                "communicationDevice=${describeDevice(communicationDevice)}; " +
                    "devices=$inventory; availableCommunication=$available; microphoneMuted=$microphoneMuted; " +
                    "inventoryIsNotActiveRouting=true; physicalInputVerified=false; physicalOutputVerified=false"
            if (deviceInventory != lastDeviceInventory) {
                lastDeviceInventory = deviceInventory
                RouterLog.event("AUDIO_DEVICE_INVENTORY", deviceInventory)
            }
            val endedAt = SystemClock.elapsedRealtime()
            RouterLog.event("AUDIO_QUERY_STAGE_COMPLETED", "operation=$operation; elapsedMs=${endedAt - operationStartedAt}")
            State(mode, device, if (manager == null) "UNAVAILABLE" else "OBSERVED", startedAt, endedAt - startedAt)
        }.getOrElse {
            RouterLog.event("AUDIO_QUERY_ERROR", "operation=$operation; type=${it.javaClass.simpleName}")
            State("UNKNOWN", "UNKNOWN", "ERROR", startedAt, SystemClock.elapsedRealtime() - startedAt, operation)
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
        query.close()
    }

    companion object {
        // Process-wide, zero-queue worker: a stuck Binder cannot leak replacement
        // threads or collect jobs from later calls/monitor instances.
        private val worker =
            ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), { task ->
                Thread(task, "router-audio-probe").apply { isDaemon = true }
            })
    }
}
