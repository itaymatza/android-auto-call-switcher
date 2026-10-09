@file:Suppress("UNUSED_PARAMETER")

package org.carcallrouter.companion

import android.content.Context

object Access {
    fun bluetoothGranted(context: Context) = context.bluetoothGranted
}

object RouterLog {
    val events = mutableListOf<Pair<String, String>>()

    fun event(
        kind: String,
        message: String,
    ) {
        events.add(kind to message)
    }

    fun deviceId(address: String?) = "alias-${address?.hashCode()}"
}
