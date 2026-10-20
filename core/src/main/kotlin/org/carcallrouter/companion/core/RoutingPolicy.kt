package org.carcallrouter.companion.core

/**
 * A bounded call-routing transaction.
 *
 * Android Auto is allowed to finish its call-start routing, one selected device request is made, and success
 * is based on target HFP audio rather than Telecom's displayed endpoint. A pre-existing mismatch
 * is reconciled with the sole stable audio owner BEFORE the single target request.
 * Maximum: one reconciliation and one target request. Failed requests are never retried.
 */
class RoutingPolicy(
    private val evidenceWindowMs: Long = 10_000,
    private val settleDelayMs: Long = 300,
    private val actionWindowMs: Long = 4_000,
    private val platformRequestTimeoutMs: Long = 1_500,
    // AOSP allows 2 s for an endpoint change; retain 500 ms for fresh observation/dispatch.
    private val reconciliationWindowMs: Long = 2_500,
    private val targetAudioStableMs: Long = 250,
    private val startupQuietMs: Long = 350,
    private val startupMaxDelayMs: Long = 900,
) {
    enum class Phase { IDLE, WAITING, VERIFYING, STABILIZING, RECOVERING_SELECTOR, RELEASED, SUSPENDED, FAILED }

    enum class Route { TARGET, COMPETING_DEVICE, OTHER_BLUETOOTH, SPEAKER, HANDSET, WIRED, STREAMING, UNKNOWN }

    enum class RequestError { TIMEOUT, ENDPOINT_GONE, CANCELLED_BY_OTHER, UNSPECIFIED, RUNTIME_EXCEPTION }

    enum class ReasonCode {
        NO_SESSION,
        WAITING_PREREQUISITES,
        WAITING_CALL_ANSWER,
        SETTLING_AFTER_ACTIVE,
        EXTERNAL_SUSPEND,
        SETTINGS_CHANGED,
        ALREADY_ACTIVE_BIND,
        CONFERENCE_OBSERVED,
        NO_FRESH_ACTIVE_TRANSITION,
        EXTERNAL_ENDPOINT_REQUEST,
        USER_PAUSED,
        AUTHORIZATION_MISSING,
        AUTHORIZATION_UNKNOWN,
        UNSAFE_CALL,
        WAITING_CALL_SAFETY,
        MULTIPLE_CALLS,
        CALL_NOT_ACTIVE,
        DISABLED,
        USER_OVERRIDE,
        STARTUP_COMPLETE,
        EVIDENCE_DEADLINE_EXPIRED,
        ACTION_DEADLINE_EXPIRED,
        WAITING_PROJECTION,
        PROJECTION_UNKNOWN,
        PROJECTION_DISCONNECTED,
        WAITING_TARGET_HFP,
        TARGET_HFP_UNKNOWN,
        TARGET_HFP_DISCONNECTED,
        WAITING_ENDPOINT_SNAPSHOT,
        WAITING_TARGET_ENDPOINT,
        WAITING_FRESH_ENDPOINT_SNAPSHOT,
        TARGET_ENDPOINT_CONFIRMED,
        TARGET_ENDPOINT_STABILIZING,
        TARGET_AUDIO_CONFIRMING,
        TARGET_AUDIO_CONFIRMED,
        TARGET_AUDIO_NOT_CONFIRMED,
        WAITING_RECONCILIATION_EVIDENCE,
        SELECTOR_RECOVERY_SUBMITTED,
        SELECTOR_RECOVERY_ACCEPTED,
        SELECTOR_RECOVERY_NOT_CONFIRMED,
        SELECTOR_RECOVERY_FAILED,
        RECONCILIATION_OWNER_CHANGED,
        ROUTE_MOVED_AFTER_CONFIRMATION,
        ALTERNATIVE_ROUTE_DURING_REQUEST,
        ALTERNATIVE_ROUTE_DEBOUNCE,
        REQUEST_NOT_VERIFIED,
        REQUEST_ACCEPTED,
        REQUEST_ACCEPTED_NOT_OBSERVED,
        REQUEST_TIMED_OUT,
        REQUEST_ENDPOINT_GONE,
        REQUEST_CANCELLED_BY_OTHER,
        REQUEST_BUDGET_EXHAUSTED,
        REQUEST_SUBMITTED,
        REQUEST_FAILED,
    }

    data class Snapshot(
        val now: Long,
        val enabled: Boolean,
        val authorized: Boolean?,
        val active: Boolean,
        val singleCall: Boolean,
        val safeCellularCall: Boolean,
        val callSafetyPending: Boolean,
        val projection: Boolean?,
        /** null means the Bluetooth profile service cannot currently provide evidence. */
        val targetHfpConnected: Boolean?,
        /** True only while the configured HFP device owns call audio. */
        val targetHfpAudio: Boolean?,
        /** Start timestamp of the HFP query; missing or stale samples cannot authorize routing. */
        val targetHfpSampleAt: Long?,
        /** True only when a uniquely resolved non-target endpoint currently owns HFP audio. */
        val selectorRecoveryAvailable: Boolean?,
        /** null means Telecom has not delivered its first endpoint snapshot yet. */
        val targetAvailable: Boolean?,
        /** Monotonically increases for every available-endpoint callback in this call session. */
        val endpointRevision: Long,
        /** Diagnostic only. It is not accepted as proof that call audio reached the target. */
        val route: Route,
        /** Exact live endpoint of the sole observed non-target HFP audio owner. */
        val recoveryEndpointKey: String? = null,
        val recoveryEndpointCurrent: Boolean = false,
    )

    /** A decision can submit at most one command; its destination is fixed by this observation. */
    sealed interface Command {
        data class Target(
            val attempt: Int,
        ) : Command

        data class Reconcile(
            val endpointKey: String,
        ) : Command
    }

    data class Decision(
        val command: Command? = null,
        val wakeAt: Long? = null,
    ) {
        val requestTarget: Boolean get() = command is Command.Target
        val restoreSelector: Boolean get() = command is Command.Reconcile
        val requestAttempt: Int? = (command as? Command.Target)?.attempt
    }

    var phase = Phase.IDLE
        private set
    var reason = "No call session"
        private set
    var reasonCode = ReasonCode.NO_SESSION
        private set
    var requests = 0
        private set
    var selectorRecoveries = 0
        private set
    var verified = false
        private set

    /** Missing evidence can move a submitted transaction back to WAITING without ending it. */
    val hasUnfinishedRequest: Boolean
        get() = hasActed && phase !in terminalPhases

    private var manual = false
    private var evidenceDeadline = 0L
    private var settleUntil = 0L
    private var startedAt = 0L
    private var actionDeadline: Long? = null
    private var inFlightAttempt: Int? = null
    private var inFlightUntil: Long? = null
    private var targetAudioSince: Long? = null
    private var lastPositiveSampleAt: Long? = null

    private data class Reconciliation(
        val endpointKey: String,
        val startedAt: Long,
        val deadline: Long,
        val accepted: Boolean = false,
    )

    private data class OwnerCandidate(
        val key: String,
        val since: Long,
        val sampledAt: Long,
    )

    private var reconciliation: Reconciliation? = null
    private var ownerCandidate: OwnerCandidate? = null
    private var externalRequestAfterTarget = false

    val pendingRecoveryEndpointKey: String?
        get() = reconciliation?.endpointKey.takeIf { phase !in terminalPhases }

    val hasActed: Boolean get() = requests > 0 || selectorRecoveries > 0

    fun begin(
        now: Long,
        initial: Route,
        manualOneShot: Boolean = false,
    ) {
        manual = manualOneShot
        startedAt = now
        evidenceDeadline = now + evidenceWindowMs
        settleUntil = now + if (manual) 0 else settleDelayMs
        actionDeadline = null
        inFlightAttempt = null
        inFlightUntil = null
        targetAudioSince = null
        lastPositiveSampleAt = null
        reconciliation = null
        ownerCandidate = null
        externalRequestAfterTarget = false
        requests = 0
        selectorRecoveries = 0
        verified = false
        phase = Phase.WAITING
        reason =
            if (manual) {
                "Manual one-shot: awaiting prerequisites"
            } else {
                "Waiting for Android Auto call-start routing to settle"
            }
        reasonCode = if (manual) ReasonCode.WAITING_PREREQUISITES else ReasonCode.SETTLING_AFTER_ACTIVE
        @Suppress("UNUSED_VARIABLE")
        val diagnosticInitialRoute = initial
    }

    /** Answer starts a new evidence window, without replacing an outstanding dialing request. */
    fun answer(
        now: Long,
        initial: Route,
    ) {
        if (phase == Phase.SUSPENDED) return
        if (hasUnfinishedRequest) {
            actionDeadline = now + actionWindowMs
            targetAudioSince = null
            lastPositiveSampleAt = null
            return
        }
        begin(now, initial)
    }

    /** Pre-answer observations warm evidence but must never compete with call-start routing. */
    fun awaitAnswer(): Decision {
        if (phase in terminalPhases) return Decision()
        phase = Phase.WAITING
        reason = "Waiting for answer; collecting call-start evidence without changing audio"
        reasonCode = ReasonCode.WAITING_CALL_ANSWER
        return Decision()
    }

    fun suspend(
        reason: String,
        code: ReasonCode = ReasonCode.EXTERNAL_SUSPEND,
    ) {
        phase = Phase.SUSPENDED
        this.reason = reason
        reasonCode = code
        clearPendingRequest()
        targetAudioSince = null
    }

    /**
     * Route callbacks are intentionally observational. They cannot reliably distinguish Samsung
     * call-start routing from a deliberate user choice. Recovery requires separate, fresh HFP
     * ownership evidence and is inhibited by external endpoint requests.
     */
    fun observeRoute(
        route: Route,
        now: Long,
    ) {
        @Suppress("UNUSED_VARIABLE")
        val diagnosticObservation = route to now
    }

    /** Wait for call-start routing activity to quiet down, but never postpone it indefinitely. */
    fun observeStartupActivity(now: Long) {
        if (manual || phase != Phase.WAITING || hasActed || now < startedAt) return
        settleUntil = minOf(startedAt + startupMaxDelayMs, maxOf(settleUntil, now + startupQuietMs))
    }

    /** Another service may control the endpoint. Keep observing audio without fighting it. */
    fun observeExternalRequestAfterTarget(): Boolean {
        if (!hasActed || phase in terminalPhases) return false
        externalRequestAfterTarget = true
        if (reconciliation != null) {
            suspend("Another endpoint request interrupted reconciliation", ReasonCode.EXTERNAL_ENDPOINT_REQUEST)
        }
        return true
    }

    fun requestSucceeded(
        attempt: Int,
        now: Long,
    ) {
        if (inFlightAttempt != attempt || phase in terminalPhases) return
        clearPendingRequest()
        phase = Phase.VERIFYING
        reason = "Telecom accepted the target request; awaiting selected device HFP audio"
        reasonCode = ReasonCode.REQUEST_ACCEPTED
        @Suppress("UNUSED_VARIABLE")
        val callbackAt = now
    }

    fun requestFailed(
        attempt: Int,
        error: RequestError,
    ) {
        if (inFlightAttempt != attempt || phase in terminalPhases) return
        clearPendingRequest()
        when (error) {
            RequestError.TIMEOUT -> {
                // A timeout can race the actual Bluetooth transition. Do not immediately retry;
                // keep observing target HFP audio until the bounded action deadline.
                phase = Phase.VERIFYING
                reason = "Telecom request timed out; awaiting selected device HFP audio without retrying"
                reasonCode = ReasonCode.REQUEST_TIMED_OUT
            }

            RequestError.ENDPOINT_GONE -> {
                fail("Selected device endpoint disappeared during the one-shot request", ReasonCode.REQUEST_ENDPOINT_GONE)
            }

            RequestError.CANCELLED_BY_OTHER -> {
                suspend(
                    "Another endpoint request replaced the one-shot request; automation stopped",
                    ReasonCode.REQUEST_CANCELLED_BY_OTHER,
                )
            }

            RequestError.UNSPECIFIED, RequestError.RUNTIME_EXCEPTION -> {
                fail("Telecom rejected the one-shot routing request", ReasonCode.REQUEST_FAILED)
            }
        }
    }

    fun selectorRecoverySucceeded() {
        val pending = reconciliation ?: return
        if (phase in terminalPhases) return
        reconciliation = pending.copy(accepted = true)
        reason = "Telecom accepted reconciliation; awaiting the observed owner endpoint"
        reasonCode = ReasonCode.SELECTOR_RECOVERY_ACCEPTED
    }

    fun selectorRecoveryFailed() {
        if (reconciliation == null || phase in terminalPhases) return
        fail(
            "Could not reconcile the displayed endpoint with the observed call-audio owner",
            ReasonCode.SELECTOR_RECOVERY_FAILED,
        )
    }

    fun evaluate(s: Snapshot): Decision {
        if (phase in terminalPhases) return Decision()
        if (s.authorized == false) return stop("Telecom authorization is missing or revoked", ReasonCode.AUTHORIZATION_MISSING)
        if (s.authorized == null) {
            if (!s.singleCall) return stop("Multiple calls / conference: system routing retained", ReasonCode.MULTIPLE_CALLS)
            if (!s.active) return stop("Call is no longer ACTIVE", ReasonCode.CALL_NOT_ACTIVE)
            if (!s.safeCellularCall &&
                !s.callSafetyPending
            ) {
                return stop("Emergency, non-cellular or unclassified call", ReasonCode.UNSAFE_CALL)
            }
            if (!manual && !s.enabled) return stop("Master toggle is off", ReasonCode.DISABLED)
            if (hasActed) return stop("Telecom authorization evidence expired after a request", ReasonCode.AUTHORIZATION_MISSING)
            val decision = waitFor("Waiting for fresh Telecom authorization evidence", ReasonCode.AUTHORIZATION_UNKNOWN, s.now)
            return decision.copy(wakeAt = decision.wakeAt?.let { minOf(it, s.now + 250) })
        }
        if (s.callSafetyPending) {
            if (!s.singleCall) return stop("Multiple calls / conference: system routing retained", ReasonCode.MULTIPLE_CALLS)
            if (!s.active) return stop("Call is no longer ACTIVE", ReasonCode.CALL_NOT_ACTIVE)
            if (!manual && !s.enabled) return stop("Master toggle is off", ReasonCode.DISABLED)
            if (hasActed) return stop("Call-safety evidence changed or expired after a request", ReasonCode.UNSAFE_CALL)
            val decision = waitFor("Waiting for fresh call-safety evidence", ReasonCode.WAITING_CALL_SAFETY, s.now)
            return decision.copy(wakeAt = decision.wakeAt?.let { minOf(it, s.now + 250) })
        }
        if (!s.safeCellularCall) return stop("Emergency, non-cellular or unclassified call", ReasonCode.UNSAFE_CALL)
        if (!s.singleCall) return stop("Multiple calls / conference: system routing retained", ReasonCode.MULTIPLE_CALLS)
        if (!s.active) return stop("Call is no longer ACTIVE", ReasonCode.CALL_NOT_ACTIVE)
        if (!manual && !s.enabled) return stop("Master toggle is off", ReasonCode.DISABLED)
        if (!manual && s.projection == false) {
            if (hasActed) return stop("Android Auto projection is not active", ReasonCode.PROJECTION_DISCONNECTED)
            return waitFor("Rechecking Android Auto before the first request", ReasonCode.PROJECTION_DISCONNECTED, s.now)
        }
        if (!manual && s.projection == null) {
            return waitFor("Waiting for Android Auto projection evidence", ReasonCode.PROJECTION_UNKNOWN, s.now)
        }
        reconciliation?.let { pending ->
            if (s.route in protectedUserRoutes && !s.recoveryEndpointCurrent) {
                return stop("Protected route selected during reconciliation", ReasonCode.USER_OVERRIDE)
            }
            if (s.selectorRecoveryAvailable == false ||
                s.targetHfpConnected == false ||
                s.targetAvailable == false ||
                (s.recoveryEndpointKey != null && s.recoveryEndpointKey != pending.endpointKey)
            ) {
                return fail("Audio owner or endpoint evidence changed during reconciliation", ReasonCode.RECONCILIATION_OWNER_CHANGED)
            }
            val sampleAt = s.targetHfpSampleAt
            val fresh = sampleAt != null && s.now - sampleAt in 0..MAX_HFP_SAMPLE_AGE_MS
            if (s.recoveryEndpointCurrent &&
                s.recoveryEndpointKey == pending.endpointKey &&
                s.selectorRecoveryAvailable == true &&
                s.targetHfpConnected == true &&
                s.targetAvailable == true &&
                fresh &&
                requireNotNull(sampleAt) > pending.startedAt &&
                s.now < pending.deadline
            ) {
                reconciliation = null
                // The observed endpoint and a strictly newer ownership sample permit the
                // single target request. An accepted outcome alone cannot advance this.
                return submitTarget(s.now)
            }
            if (s.now >= pending.deadline) {
                return fail(
                    if (pending.accepted) {
                        "Telecom accepted reconciliation but the observed owner endpoint was not confirmed"
                    } else {
                        "Reconciliation timed out before the observed owner endpoint was confirmed"
                    },
                    ReasonCode.SELECTOR_RECOVERY_NOT_CONFIRMED,
                )
            }
            phase = Phase.RECOVERING_SELECTOR
            return Decision(wakeAt = pending.deadline)
        }
        if (s.targetHfpConnected == false) {
            if (hasActed) return stop("Selected call device is not connected for HFP", ReasonCode.TARGET_HFP_DISCONNECTED)
            return waitFor("Waiting for the selected Bluetooth call device to connect", ReasonCode.WAITING_TARGET_HFP, s.now)
        }
        if (s.targetHfpConnected == null) {
            return waitFor("Waiting for Bluetooth HFP evidence", ReasonCode.TARGET_HFP_UNKNOWN, s.now)
        }
        val sampleAt = s.targetHfpSampleAt
        val sampleFresh = sampleAt != null && s.now - sampleAt in 0..MAX_HFP_SAMPLE_AGE_MS
        val targetAudio = if (sampleFresh) s.targetHfpAudio else null
        val candidateKey = s.recoveryEndpointKey.takeIf { sampleFresh && targetAudio == false && s.selectorRecoveryAvailable == true }
        val previousCandidate = ownerCandidate
        ownerCandidate =
            candidateKey?.let { key ->
                val at = requireNotNull(sampleAt)
                val continuous =
                    previousCandidate != null &&
                        previousCandidate.key == key &&
                        at - previousCandidate.sampledAt in 0..MAX_HFP_SAMPLE_AGE_MS
                OwnerCandidate(key, if (continuous) requireNotNull(previousCandidate).since else at, at)
            }
        if (s.now < settleUntil) {
            targetAudioSince = null
            phase = Phase.WAITING
            reason = "Waiting for Android Auto call-start routing to settle"
            reasonCode = ReasonCode.SETTLING_AFTER_ACTIVE
            return Decision(wakeAt = minOf(settleUntil, evidenceDeadline))
        }
        if (actionDeadline == null && s.now >= evidenceDeadline) {
            return fail("Evidence deadline expired before safe routing could start", ReasonCode.EVIDENCE_DEADLINE_EXPIRED)
        }

        if (targetAudio == true) {
            val observedAt = requireNotNull(sampleAt)
            if (observedAt > currentDeadline()) {
                return fail("Audio confirmation arrived after its observation deadline", ReasonCode.TARGET_AUDIO_NOT_CONFIRMED)
            }
            val previous = lastPositiveSampleAt
            if (previous != null && (observedAt < previous || observedAt - previous > MAX_HFP_SAMPLE_AGE_MS)) {
                targetAudioSince = null
            }
            lastPositiveSampleAt = observedAt
            val since = targetAudioSince ?: observedAt.also { targetAudioSince = it }
            val stableAt = since + targetAudioStableMs
            // Time passing on one cached sample cannot demonstrate stable physical audio.
            if (observedAt < stableAt) {
                if (s.now >= currentDeadline()) {
                    return fail(
                        "Selected device HFP audio did not remain stable within the bounded window",
                        ReasonCode.TARGET_AUDIO_NOT_CONFIRMED,
                    )
                }
                phase = Phase.STABILIZING
                reason = "Selected device owns HFP audio; confirming it remains stable"
                reasonCode = ReasonCode.TARGET_AUDIO_CONFIRMING
                return Decision(wakeAt = minOf(maxOf(stableAt, s.now + 250), currentDeadline()))
            }
            verified = true
            phase = Phase.RELEASED
            reason = "Selected device HFP audio confirmed; bounded routing complete"
            reasonCode = ReasonCode.TARGET_AUDIO_CONFIRMED
            clearPendingRequest()
            return Decision()
        }
        targetAudioSince = null
        lastPositiveSampleAt = null

        if (requests == 0) {
            // Availability is required to submit the request. Once submitted, Telecom may
            // replace its endpoint snapshot while the Bluetooth audio transition completes.
            // Exact, stable target HFP audio remains the stronger post-request evidence.
            if (s.targetAvailable != true) {
                val code = if (s.targetAvailable == null) ReasonCode.WAITING_ENDPOINT_SNAPSHOT else ReasonCode.WAITING_TARGET_ENDPOINT
                return waitFor("Waiting for selected device in Telecom's endpoint list", code, s.now)
            }
            if (targetAudio == null) {
                return waitFor("Waiting for current HFP audio ownership", ReasonCode.TARGET_HFP_UNKNOWN, s.now)
            }
            if (s.route == Route.TARGET) {
                val candidate = ownerCandidate
                if (candidate == null || candidate.sampledAt - candidate.since < OWNER_STABLE_MS) {
                    val waiting =
                        waitFor(
                            "Displayed endpoint and audio disagree; waiting for a stable, uniquely resolved audio owner",
                            ReasonCode.WAITING_RECONCILIATION_EVIDENCE,
                            s.now,
                        )
                    return waiting.copy(wakeAt = waiting.wakeAt?.let { minOf(it, s.now + 250) })
                }
                check(selectorRecoveries == 0) { "Reconciliation budget exhausted" }
                selectorRecoveries = 1
                val pending = Reconciliation(candidate.key, s.now, s.now + reconciliationWindowMs)
                reconciliation = pending
                phase = Phase.RECOVERING_SELECTOR
                reason = "Reconciling the displayed endpoint with the observed audio owner before the single target request"
                reasonCode = ReasonCode.SELECTOR_RECOVERY_SUBMITTED
                return Decision(Command.Reconcile(candidate.key), pending.deadline)
            }
            return submitTarget(s.now)
        }

        inFlightUntil?.let { timeout ->
            if (s.now < timeout) return Decision(wakeAt = minOf(timeout, currentDeadline()))
            clearPendingRequest()
            phase = Phase.VERIFYING
            reason = "Telecom callback deadline expired; awaiting HFP audio without retrying"
            reasonCode = ReasonCode.REQUEST_TIMED_OUT
        }

        if (s.now >= currentDeadline()) {
            return fail(
                if (externalRequestAfterTarget) {
                    "Another endpoint request occurred; selected device audio was not confirmed and no route was restored"
                } else {
                    "Selected device HFP audio was not confirmed after the one-shot request"
                },
                if (externalRequestAfterTarget) ReasonCode.EXTERNAL_ENDPOINT_REQUEST else ReasonCode.TARGET_AUDIO_NOT_CONFIRMED,
            )
        }
        phase = Phase.VERIFYING
        return Decision(wakeAt = currentDeadline())
    }

    private fun submitTarget(now: Long): Decision {
        check(requests == 0) { "Target request budget exhausted" }
        requests++
        actionDeadline = now + actionWindowMs
        inFlightAttempt = requests
        inFlightUntil = now + platformRequestTimeoutMs
        phase = Phase.VERIFYING
        reason = "Selected device request submitted; awaiting fresh HFP audio confirmation"
        reasonCode = ReasonCode.REQUEST_SUBMITTED
        return Decision(Command.Target(requests), wakeAt = minOf(currentDeadline(), requireNotNull(inFlightUntil)))
    }

    private fun waitFor(
        message: String,
        code: ReasonCode,
        now: Long,
    ): Decision {
        val deadline = currentDeadline()
        if (now >= deadline) {
            if (reconciliation != null) {
                return fail("Fresh evidence was unavailable before the reconciliation deadline", ReasonCode.SELECTOR_RECOVERY_NOT_CONFIRMED)
            }
            val code = if (actionDeadline == null) ReasonCode.EVIDENCE_DEADLINE_EXPIRED else ReasonCode.TARGET_AUDIO_NOT_CONFIRMED
            val failure =
                if (actionDeadline == null) {
                    "Evidence deadline expired before safe routing could start"
                } else {
                    "Selected device HFP audio was not confirmed after the one-shot request"
                }
            return fail(failure, code)
        }
        phase = Phase.WAITING
        reason = message
        reasonCode = code
        return Decision(wakeAt = deadline)
    }

    private fun currentDeadline(): Long = reconciliation?.deadline ?: actionDeadline ?: evidenceDeadline

    private fun clearPendingRequest() {
        inFlightAttempt = null
        inFlightUntil = null
    }

    private fun stop(
        message: String,
        code: ReasonCode,
    ): Decision {
        suspend(message, code)
        return Decision()
    }

    private fun fail(
        message: String,
        code: ReasonCode,
    ): Decision {
        phase = Phase.FAILED
        reason = message
        reasonCode = code
        clearPendingRequest()
        targetAudioSince = null
        return Decision()
    }

    companion object {
        const val MAX_HFP_SAMPLE_AGE_MS = 750L
        private const val OWNER_STABLE_MS = 500L
        val protectedUserRoutes = setOf(Route.SPEAKER, Route.HANDSET, Route.WIRED, Route.OTHER_BLUETOOTH)
        val terminalPhases = setOf(Phase.IDLE, Phase.RELEASED, Phase.SUSPENDED, Phase.FAILED)
    }
}
