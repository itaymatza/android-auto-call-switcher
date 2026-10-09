package org.carcallrouter.companion.telecom

import android.content.Context

internal class AudioFrameworkProbe(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : AutoCloseable {
    data class State(
        val mode: String,
        val communicationDevice: String,
        val quality: String = "OBSERVED",
        val sampledAt: Long? = null,
        val queryMs: Long? = null,
        val failedOperation: String? = null,
        val diagnosticQuality: String = "OBSERVED",
        val diagnosticSampledAt: Long? = null,
        val diagnosticQueryMs: Long? = null,
        val diagnosticFailedOperation: String? = null,
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
