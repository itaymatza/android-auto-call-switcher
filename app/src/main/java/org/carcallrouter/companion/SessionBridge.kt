package org.carcallrouter.companion

import android.os.SystemClock
import org.carcallrouter.companion.core.CallAudioStatus
import org.carcallrouter.companion.core.RoutingPolicy
import java.lang.ref.WeakReference

/** In-process only; no exported receiver can request routing. Main thread ownership. */
object SessionBridge {
    interface Control {
        fun routeNow()

        fun pauseSession()

        fun reportWrongAudio()
    }

    var controller: WeakReference<Control>? = null
    var status = "No Telecom service bound. This is normal between calls."

    private data class AudioObservation(
        val status: CallAudioStatus,
        val sampledAt: Long?,
    )

    private var audioObservation = AudioObservation(CallAudioStatus.NO_CALL, null)
    private val sampledStatuses =
        setOf(
            CallAudioStatus.TARGET_OBSERVED,
            CallAudioStatus.MISMATCH,
            CallAudioStatus.OTHER_DEVICE,
            CallAudioStatus.NO_BLUETOOTH_AUDIO,
        )

    /** UI refreshes cannot make a cached query fresh, even if service polling has stopped. */
    val audioStatus: CallAudioStatus
        get() {
            val observation = audioObservation
            val fresh = observation.sampledAt?.let { SystemClock.elapsedRealtime() - it in 0..RoutingPolicy.MAX_HFP_SAMPLE_AGE_MS } == true
            return if (observation.status in sampledStatuses && !fresh) CallAudioStatus.UNCONFIRMED else observation.status
        }
    private val listeners = linkedSetOf<() -> Unit>()

    fun publish(
        value: String,
        audio: CallAudioStatus = CallAudioStatus.NO_CALL,
        sampledAt: Long? = null,
    ) {
        val previousAudio = audioStatus
        val previousStatus = status
        status = value
        audioObservation = AudioObservation(audio, sampledAt)
        if (previousStatus != status || previousAudio != audioStatus) listeners.toList().forEach { it() }
    }

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
        listener()
    }

    fun remove(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
