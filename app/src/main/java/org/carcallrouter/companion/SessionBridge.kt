package org.carcallrouter.companion

import org.carcallrouter.companion.core.CallAudioStatus
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
    var audioStatus = CallAudioStatus.NO_CALL
        private set
    private val listeners = linkedSetOf<() -> Unit>()

    fun publish(
        value: String,
        audio: CallAudioStatus = CallAudioStatus.NO_CALL,
    ) {
        status = value
        audioStatus = audio
        listeners.toList().forEach { it() }
    }

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
        listener()
    }

    fun remove(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
