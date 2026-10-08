package org.carcallrouter.companion.telecom

import android.content.Context

internal class AudioFrameworkProbe(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : AutoCloseable {
    data class State(
        val mode: String,
        val communicationDevice: String,
    )

    fun sample(
        @Suppress("UNUSED_PARAMETER") now: Long,
    ): State = State(mode, device)

    override fun close() = Unit

    companion object {
        var mode = "IN_CALL"
        var device = "BLUETOOTH_SCO"
    }
}
