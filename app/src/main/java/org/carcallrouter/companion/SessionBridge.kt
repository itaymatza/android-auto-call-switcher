package org.carcallrouter.companion

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
    private val listeners = linkedSetOf<() -> Unit>()

    fun publish(value: String) {
        status = value
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
