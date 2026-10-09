package org.carcallrouter.companion.core

/** Monotonic times observed by the service; late binding cannot reconstruct unseen call history. */
class CallTimeline(
    private val addedAt: Long,
    initialState: String,
) {
    private val initialActive = initialState == "ACTIVE"
    private var dialingAt: Long? = null
    private var activeAt: Long? = null
    private var endedAt: Long? = null
    private var state = initialState

    init {
        observe(initialState, addedAt)
    }

    fun observe(
        newState: String,
        now: Long,
    ) {
        state = newState
        if (newState == "DIALING" && dialingAt == null) dialingAt = now
        if (newState == "ACTIVE" && activeAt == null) activeAt = now
        if (newState in setOf("DISCONNECTING", "DISCONNECTED", "REMOVED") && endedAt == null) endedAt = now
    }

    fun fields(now: Long): Array<Pair<String, Any?>> {
        val end = endedAt ?: now
        return arrayOf(
            "call_state" to state,
            "call_observed_ms" to (end - addedAt).coerceAtLeast(0),
            "since_dialing_ms" to dialingAt?.let { (now - it).coerceAtLeast(0) },
            "since_answer_ms" to activeAt?.let { (now - it).coerceAtLeast(0) },
            "dialing_duration_ms" to dialingAt?.let { ((activeAt ?: end) - it).coerceAtLeast(0) },
            "connected_duration_ms" to activeAt?.let { (end - it).coerceAtLeast(0) },
            "timing_origin" to if (initialActive) "LATE_BIND_OBSERVATION" else "OBSERVED_CALLBACKS",
            "physical_audio_verified" to false,
        )
    }
}
