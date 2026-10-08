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

/** Read-only public audio-framework evidence. It cannot identify a Bluetooth endpoint by MAC. */
internal class AudioFrameworkProbe(
    context: Context,
) : AutoCloseable {
    data class State(
        val mode: String,
        val communicationDevice: String,
    )

    private val manager = context.getSystemService(AudioManager::class.java)
    private var sampledAt = Long.MIN_VALUE
    private var cached = State("UNKNOWN", "UNKNOWN")

    private val handler = Handler(Looper.getMainLooper())
    private val query =
        SingleFlightQuery<State>(worker, Executor { handler.post(it) }, SystemClock::elapsedRealtime) { result, queuedAt, startedAt ->
            val now = SystemClock.elapsedRealtime()
            sampledAt = startedAt
            cached = if (now - startedAt <= 750) result.getOrDefault(State("UNKNOWN", "UNKNOWN")) else State("UNKNOWN", "UNKNOWN")
            RouterLog.event("AUDIO_QUERY_COMPLETED", "queueMs=${startedAt - queuedAt}; elapsedMs=${now - startedAt}; mode=${cached.mode}")
        }

    fun sample(now: Long = SystemClock.elapsedRealtime()): State {
        if (sampledAt == Long.MIN_VALUE || now - sampledAt >= 250) {
            query.submit { readState() }
        }
        return if (sampledAt != Long.MIN_VALUE && now - sampledAt in 0..750) cached else State("UNKNOWN", "UNKNOWN")
    }

    private fun readState(): State {
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
            RouterLog.event("AUDIO_QUERY_STAGE", "operation=communication_device")
            val device =
                manager?.communicationDevice?.type?.let { communicationType ->
                    when (communicationType) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
                        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
                        else -> "OTHER"
                    }
                } ?: "UNKNOWN"
            State(mode, device)
        }.getOrDefault(State("UNKNOWN", "UNKNOWN"))
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
