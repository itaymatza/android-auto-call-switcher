package org.carcallrouter.companion.core

/**
 * Produces stable, machine-readable, privacy-safe routing evidence.
 *
 * Callers must pass only enums, booleans, numbers, or already-redacted identifiers. Values are
 * tokenized again here so accidental whitespace or log delimiters cannot corrupt the record.
 */
class RoutingTrace(
    private val now: () -> Long,
    private val newSessionId: () -> String,
    private val emit: (String) -> Unit,
    private val context: () -> Array<Pair<String, Any?>> = { emptyArray() },
) {
    enum class Confirmation { NONE, TELECOM_ENDPOINT, TARGET_HFP_AUDIO }

    private var sessionId: String? = null
    private var startedAt = 0L
    private var sequence = 0
    private var confirmation = Confirmation.NONE
    private val eventCounts = mutableMapOf<String, Int>()
    private val endpointRequestClassifications = mutableMapOf<String, Int>()

    fun begin(
        mode: String,
        trigger: String,
    ) {
        if (sessionId != null) return
        sessionId = token(newSessionId())
        startedAt = now()
        sequence = 0
        confirmation = Confirmation.NONE
        eventCounts.clear()
        endpointRequestClassifications.clear()
        event("SESSION_STARTED", "mode" to mode, "trigger" to trigger)
    }

    fun event(
        name: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val id = sessionId ?: return
        val elapsed = (now() - startedAt).coerceAtLeast(0)
        val body =
            buildList {
                add("schema=1")
                add("session=$id")
                add("seq=${++sequence}")
                add("elapsed_ms=$elapsed")
                add("event=${token(name)}")
                (context().toList() + fields).forEach { (key, value) -> add("${token(key)}=${token(value?.toString() ?: "null")}") }
            }.joinToString(" ")
        emit(body)
        eventCounts[name] = (eventCounts[name] ?: 0) + 1
        if (name == "ENDPOINT_REQUEST_OBSERVED") {
            val classification = fields.firstOrNull { it.first == "classification" }?.second?.toString() ?: "unknown"
            endpointRequestClassifications[classification] =
                (endpointRequestClassifications[classification] ?: 0) + 1
        }
    }

    fun confirmTelecom() {
        if ((eventCounts["TELECOM_ENDPOINT_CONFIRMED"] ?: 0) > 0) return
        if (confirmation == Confirmation.NONE) confirmation = Confirmation.TELECOM_ENDPOINT
        event("TELECOM_ENDPOINT_CONFIRMED")
    }

    fun confirmTargetHfpAudio() {
        if ((eventCounts["TARGET_HFP_AUDIO_CONFIRMED"] ?: 0) > 0) return
        confirmation = Confirmation.TARGET_HFP_AUDIO
        event("TARGET_HFP_AUDIO_CONFIRMED")
    }

    fun finish(
        phase: RoutingPolicy.Phase,
        reason: RoutingPolicy.ReasonCode,
        termination: String,
        vararg fields: Pair<String, Any?>,
    ): Confirmation? {
        if (sessionId == null) return null
        val result = confirmation
        event(
            "SESSION_FINISHED",
            "phase" to phase,
            "reason" to reason,
            "termination" to termination,
            "best_confirmation" to confirmation,
            "requests" to (eventCounts["REQUEST_SUBMITTED"] ?: 0),
            "selector_recoveries" to (eventCounts["SELECTOR_RECOVERY_SUBMITTED"] ?: 0),
            "route_changes" to (eventCounts["ENDPOINT_CHANGED"] ?: 0),
            "endpoint_callbacks" to (eventCounts["ENDPOINT_REQUEST_OBSERVED"] ?: 0),
            "self_callbacks" to (endpointRequestClassifications["SELF"] ?: 0),
            "startup_replays" to (endpointRequestClassifications["STARTUP_REPLAY"] ?: 0),
            "external_callbacks" to (endpointRequestClassifications["EXTERNAL"] ?: 0),
            "suspensions" to (eventCounts["SESSION_SUSPENDED"] ?: 0),
            *fields,
        )
        sessionId = null
        return result
    }

    fun isActive(): Boolean = sessionId != null

    private fun token(value: String): String =
        value
            .trim()
            .replace(Regex("[^A-Za-z0-9_.:-]"), "_")
            .take(96)
            .ifEmpty { "empty" }
}
