@file:Suppress("DEPRECATION")

package org.carcallrouter.companion.telecom

import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.ProcessDiagnostics
import org.carcallrouter.companion.ProjectionMonitor
import org.carcallrouter.companion.RouterLog
import org.carcallrouter.companion.RouterSettings
import org.carcallrouter.companion.SessionBridge
import org.carcallrouter.companion.core.RoutingPolicy
import org.carcallrouter.companion.core.RoutingTrace
import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.UUID

/** Telecom-bound non-UI companion; never claims the dialer role or manipulates media profiles. */
class RouterInCallService :
    InCallService(),
    SessionBridge.Control {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: RouterSettings
    private lateinit var router: AddressedTelecomRouter
    private lateinit var classifier: CellularClassifier
    private lateinit var projectionMonitor: ProjectionMonitor
    private lateinit var hfp: HfpMonitor
    private lateinit var audioFramework: AudioFrameworkProbe
    private lateinit var trace: RoutingTrace
    private var projection: Boolean? = null
    private var sequence = 0

    // Telecom can unbind/rebind the same Call object while it is already ACTIVE. Track object
    // identity so that replay remains fail-closed without suppressing a genuinely new call that
    // happens to be first observed at ACTIVE in this long-lived service instance.
    private var lastObservedCallObject: WeakReference<Call>? = null
    private var disposed = false
    private var policy = RoutingPolicy()
    private var sessionStarted = false
    private var lastPublished = ""
    private var manualSession = false
    private var hfpStarted = false
    private var lastTracePolicy: Pair<RoutingPolicy.Phase, RoutingPolicy.ReasonCode>? = null
    private var lastTraceEvidence: String? = null
    private var lastSafety = "No calls"
    private var manualCooldownUntil = 0L
    private var activeTransitionAt: Long? = null
    private var lateBindRecoveryDeadlineAt: Long? = null
    private var lastEndpointId: String? = null
    private var scheduledTickAt: Long? = null
    private var scheduledTickElapsed: Long? = null
    private var scheduledTickUptime: Long? = null
    private var confirmedAudioPresent: Boolean? = null
    private var postConfirmationDeadlineAt: Long? = null
    private var postConfirmationWatchComplete = false
    private var postConfirmationLossObserved = false
    private var postConfirmationObservationIncomplete = false
    private var suspectedAudioLossAt: Long? = null
    private var suspectedAudioLossSampleAt: Long? = null
    private var lastHfpAudioDevices: Set<String>? = null
    private var lastAudioFrameworkState: AudioFrameworkProbe.State? = null

    private data class Record(
        val id: Int,
        val callback: Call.Callback,
        var sawPreActive: Boolean,
        var lastState: Int,
    )

    private val records = IdentityHashMap<Call, Record>()
    private val tick =
        Runnable {
            val due = scheduledTickAt
            val scheduledElapsed = scheduledTickElapsed
            val scheduledUptime = scheduledTickUptime
            scheduledTickAt = null
            scheduledTickElapsed = null
            scheduledTickUptime = null
            if (due != null && scheduledElapsed != null && scheduledUptime != null) {
                val elapsedNow = SystemClock.elapsedRealtime()
                val elapsedDelta = elapsedNow - scheduledElapsed
                val uptimeDelta = SystemClock.uptimeMillis() - scheduledUptime
                trace.event(
                    "TIMER_FIRED",
                    "due_elapsed_ms" to due,
                    "late_ms" to (elapsedNow - due).coerceAtLeast(0),
                    "elapsed_delta_ms" to elapsedDelta,
                    "uptime_delta_ms" to uptimeDelta,
                    "sleep_delta_ms" to (elapsedDelta - uptimeDelta).coerceAtLeast(0),
                )
            }
            if (policy.phase == RoutingPolicy.Phase.RELEASED &&
                postConfirmationDeadlineAt?.let { SystemClock.elapsedRealtime() > it + HFP_VERIFY_POLL_MS } == true
            ) {
                postConfirmationObservationIncomplete = true
                finishPostConfirmationWatch("observation_gap")
            }
            if (!manualSession && policy.phase == RoutingPolicy.Phase.WAITING && projection != true) {
                projectionMonitor.requestRefresh()
            }
            val settling =
                policy.phase == RoutingPolicy.Phase.WAITING &&
                    policy.reasonCode == RoutingPolicy.ReasonCode.SETTLING_AFTER_ACTIVE
            if (
                hfpStarted &&
                (
                    policy.phase == RoutingPolicy.Phase.WAITING ||
                        policy.phase in setOf(RoutingPolicy.Phase.VERIFYING, RoutingPolicy.Phase.STABILIZING) ||
                        (policy.phase == RoutingPolicy.Phase.RELEASED && postConfirmationDeadlineAt != null)
                )
            ) {
                val previousSampleAt = hfp.sampledAt
                val previousAudio = if (hfp.known) hfp.audioConnected else null
                hfp.refresh(if (settling) "settling_timer" else "verification_timer")
                if (settling && previousAudio != null && hfp.known && hfp.audioConnected != previousAudio) {
                    policy.observeStartupActivity(SystemClock.elapsedRealtime())
                    trace.event(
                        "STARTUP_AUDIO_ACTIVITY",
                        "previous_count" to previousAudio.size,
                        "current_count" to hfp.audioConnected.size,
                        "source" to "settling_sample",
                    )
                }
                if (settling) lastHfpAudioDevices = if (hfp.known) hfp.audioConnected else null
                val address = settings.targetAddress?.uppercase()
                trace.event(
                    when {
                        settling -> "HFP_SETTLING_SAMPLE_REQUESTED"
                        policy.phase == RoutingPolicy.Phase.RELEASED -> "HFP_POST_CONFIRMATION_SAMPLE_REQUESTED"
                        else -> "HFP_VERIFICATION_SAMPLE_REQUESTED"
                    },
                    "previous_sample_age_ms" to previousSampleAt?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) },
                    "sample" to hfp.sampleSequence,
                    "known" to hfp.known,
                    "audio_owner" to hfpAudioOwner(address, settings.competitorAddress?.uppercase()),
                    "audio_device" to hfp.audioConnected.singleOrNull()?.let(RouterLog::deviceId),
                    "telecom_route" to currentRoute(),
                )
            }
            evaluate()
        }
    private val evaluateEvent = Runnable { evaluate() }

    private fun cancelTick(reason: String) {
        handler.removeCallbacks(tick)
        scheduledTickAt?.let {
            trace.event(
                "TIMER_CANCELLED",
                "due_elapsed_ms" to it,
                "remaining_ms" to (it - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                "reason" to reason,
            )
        }
        scheduledTickAt = null
        scheduledTickElapsed = null
        scheduledTickUptime = null
    }

    private fun scheduleTick(
        due: Long,
        reason: String,
    ) {
        val elapsed = SystemClock.elapsedRealtime()
        val uptime = SystemClock.uptimeMillis()
        val delay = (due - elapsed).coerceAtLeast(1)
        scheduledTickAt = due
        scheduledTickElapsed = elapsed
        scheduledTickUptime = uptime
        val posted = handler.postDelayed(tick, delay)
        trace.event(
            "TIMER_SCHEDULED",
            "reason" to reason,
            "due_elapsed_ms" to due,
            "delay_ms" to delay,
            "posted" to posted,
        )
        if (!posted) {
            scheduledTickAt = null
            scheduledTickElapsed = null
            scheduledTickUptime = null
        }
    }

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // Diagnostic writes (last_bound / last_session_*) share this preference file but do
            // not alter routing. Treating them as configuration changes can start an orphan trace
            // while onCallRemoved() is persisting the result of the trace that just finished.
            if (key in RouterSettings.ROUTING_CONFIGURATION_KEYS && sessionStarted) {
                policy.suspend(
                    "Settings changed during a call; automatic routing paused for this session",
                    RoutingPolicy.ReasonCode.SETTINGS_CHANGED,
                )
                trace.event("SESSION_SUSPENDED", "reason" to policy.reasonCode)
                queueEvaluation()
            }
        }

    override fun onCreate() {
        super.onCreate()
        startObservers()
    }

    private fun startObservers() {
        disposed = false
        policy = RoutingPolicy()
        sessionStarted = false
        manualSession = false
        hfpStarted = false
        lastTracePolicy = null
        lastTraceEvidence = null
        lastAudioFrameworkState = null
        projection = null
        lastPublished = ""
        activeTransitionAt = null
        lateBindRecoveryDeadlineAt = null
        lastEndpointId = null
        confirmedAudioPresent = null
        postConfirmationDeadlineAt = null
        postConfirmationWatchComplete = false
        postConfirmationLossObserved = false
        postConfirmationObservationIncomplete = false
        suspectedAudioLossAt = null
        suspectedAudioLossSampleAt = null
        scheduledTickAt = null
        scheduledTickElapsed = null
        scheduledTickUptime = null
        trace =
            RoutingTrace(
                now = SystemClock::elapsedRealtime,
                newSessionId = {
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(12)
                },
                emit = { RouterLog.event("ROUTING_TRACE", it) },
            )
        settings = RouterSettings(this)
        router = AddressedTelecomRouter(this)
        classifier = CellularClassifier(this)
        hfp = newHfpMonitor()
        audioFramework = AudioFrameworkProbe(this)
        projectionMonitor =
            ProjectionMonitor(this) { value ->
                val previous = projection
                projection = value
                if (trace.isActive() && value != previous) {
                    trace.event("PROJECTION_CHANGED", "active" to value, "previous" to previous)
                }
                if (value == true) ensureHfpMonitoring("projection_active")
                if (value == false && !manualSession) stopHfpMonitoring("projection_inactive")
                if (!manualSession && guardHasActed() && value == false) {
                    suspendSessionFromEvent(
                        "Projection disconnected; this session stays paused",
                        RoutingPolicy.ReasonCode.PROJECTION_DISCONNECTED,
                    )
                }
                queueEvaluation()
            }
        settings.prefs.registerOnSharedPreferenceChangeListener(prefListener)
        SessionBridge.controller = WeakReference(this)
        RouterLog.event(
            "SERVICE_CREATE",
            "non-UI service; authorized=${Access.ongoingCalls(this)}; ${ProcessDiagnostics.snapshot(this)}",
        )
        projectionMonitor.start()
    }

    private fun newHfpMonitor(): HfpMonitor =
        HfpMonitor(this) {
            val audio = if (hfp.known) hfp.audioConnected else null
            if (audio != null && lastHfpAudioDevices != null && audio != lastHfpAudioDevices) {
                val before = lastHfpAudioDevices?.size ?: 0
                policy.observeStartupActivity(SystemClock.elapsedRealtime())
                if (policy.phase == RoutingPolicy.Phase.WAITING && policy.requests == 0) {
                    trace.event("STARTUP_AUDIO_ACTIVITY", "previous_count" to before, "current_count" to audio.size)
                }
            }
            lastHfpAudioDevices = audio
            // Cancellation edges must survive a disconnect/reconnect before queued evaluation.
            val address = settings.targetAddress?.uppercase()
            if (guardHasActed() && address != null && hfp.known && address !in hfp.connected) {
                suspendSessionFromEvent(
                    "target Bluetooth HFP disappeared; this session stays paused",
                    RoutingPolicy.ReasonCode.TARGET_HFP_DISCONNECTED,
                )
            }
            queueEvaluation()
        }

    private fun ensureHfpMonitoring(reason: String) {
        if (hfpStarted) return
        hfpStarted = true
        RouterLog.event("HFP_MONITOR_START", "reason=$reason")
        hfp.start()
    }

    private fun stopHfpMonitoring(reason: String) {
        if (!hfpStarted) return
        RouterLog.event("HFP_MONITOR_STOP", "reason=$reason")
        hfp.close()
        hfp = newHfpMonitor()
        hfpStarted = false
        lastHfpAudioDevices = null
    }

    override fun onBind(intent: Intent): IBinder {
        if (disposed) startObservers()
        settings.markBound()
        RouterLog.event("TELECOM_BOUND", "System bound the service; ${ProcessDiagnostics.snapshot(this)}")
        return requireNotNull(super.onBind(intent))
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val replayedCallObject = lastObservedCallObject?.get() === call
        lastObservedCallObject = WeakReference(call)
        val id = ++sequence
        val callback =
            object : Call.Callback() {
                override fun onStateChanged(
                    call: Call,
                    state: Int,
                ) {
                    handleState(call, state)
                }

                override fun onDetailsChanged(
                    call: Call,
                    details: Call.Details,
                ) {
                    handleState(call, details.state)
                }

                override fun onChildrenChanged(
                    call: Call,
                    children: MutableList<Call>,
                ) {
                    if (children.isNotEmpty()) {
                        suspendSessionFromEvent(
                            "Conference children observed; system retains routing",
                            RoutingPolicy.ReasonCode.CONFERENCE_OBSERVED,
                        )
                    }
                    queueEvaluation()
                }
            }
        val state = call.details.state
        val record = Record(id, callback, isPreActive(state), state)
        records[call] = record
        if (records.size > 1) {
            // Do not let rapid call removal erase the fact that the session was ambiguous.
            suspendSessionFromEvent(
                "Another call observed; system retains routing for this session",
                RoutingPolicy.ReasonCode.MULTIPLE_CALLS,
            )
        }
        call.registerCallback(callback, handler)
        RouterLog.event(
            "CALL_ADDED",
            "call=$id; state=${stateName(state)}; preActiveObserved=${record.sawPreActive}; " +
                ProcessDiagnostics.snapshot(this),
        )
        if (!sessionStarted && state == Call.STATE_ACTIVE) {
            // Samsung can bind this non-UI service after its first observable call state is
            // already ACTIVE. Defer the decision until fresh projection/HFP/endpoint evidence is
            // available; recovery is permitted only from a verified car-owned route.
            sessionStarted = true
            manualSession = false
            activeTransitionAt = SystemClock.elapsedRealtime()
            if (replayedCallObject) {
                beginTrace("automatic", "same_instance_active_rebind")
                policy.suspend(
                    "Same already-active call object returned to the service; automatic takeover suppressed",
                    RoutingPolicy.ReasonCode.ALREADY_ACTIVE_BIND,
                )
                trace.event("SESSION_SUSPENDED", "reason" to policy.reasonCode)
            } else {
                lateBindRecoveryDeadlineAt = activeTransitionAt?.plus(LATE_BIND_EVIDENCE_WINDOW_MS)
                beginTrace("automatic", "already_active_bind_pending")
                trace.event("LATE_BIND_RECOVERY_PENDING", "initial_route" to currentRoute())
                RouterLog.event(
                    "LATE_BIND_RECOVERY_PENDING",
                    "Awaiting verified Android Auto, BMW HFP and endpoint evidence",
                )
            }
        }
        queueEvaluation()
    }

    private fun handleState(
        call: Call,
        state: Int,
    ) {
        val record = records[call] ?: return
        val previous = record.lastState
        record.lastState = state
        if (sessionStarted && state != Call.STATE_ACTIVE) {
            // Use the callback state, not call.details which can already contain a later state.
            suspendSessionFromEvent(
                "Observed ${stateName(state)}; no reassertion on resume",
                RoutingPolicy.ReasonCode.CALL_NOT_ACTIVE,
            )
        }
        if (isPreActive(state)) record.sawPreActive = true
        if (state != previous) RouterLog.event("CALL_STATE", "call=${record.id}; ${stateName(previous)} -> ${stateName(state)}")
        if (!sessionStarted && state == Call.STATE_ACTIVE) {
            sessionStarted = true
            manualSession = false
            activeTransitionAt = SystemClock.elapsedRealtime()
            lateBindRecoveryDeadlineAt = null
            if (record.sawPreActive && previous != Call.STATE_HOLDING) {
                beginTrace("automatic", "fresh_active_transition")
                policy.begin(SystemClock.elapsedRealtime(), currentRoute())
                trace.event("ACTIVE_TRANSITION", "call" to record.id, "initial_route" to currentRoute())
                RouterLog.event(
                    "SESSION_START",
                    "call=${record.id}; detected ACTIVE transition; target=${RouterLog.deviceId(settings.targetAddress)}",
                )
            } else {
                beginTrace("automatic", "active_without_fresh_transition")
                policy.suspend(
                    "No fresh answered/connected transition observed",
                    RoutingPolicy.ReasonCode.NO_FRESH_ACTIVE_TRANSITION,
                )
                trace.event("SESSION_SUSPENDED", "reason" to policy.reasonCode)
            }
        }
        if (state != previous && trace.isActive()) {
            trace.event(
                "CALL_STATE_CHANGED",
                "call" to record.id,
                "previous" to stateName(previous),
                "current" to stateName(state),
                "records" to records.size,
            )
        }
        queueEvaluation()
    }

    override fun onCallRemoved(call: Call) {
        records.remove(call)?.let {
            call.unregisterCallback(it.callback)
            RouterLog.event("CALL_REMOVED", "call=${it.id}")
        }
        if (records.isEmpty()) {
            cancelTick("call_removed")
            finishPostConfirmationWatch("call_removed")
            val address = settings.targetAddress?.uppercase()
            val confirmation =
                trace.finish(
                    policy.phase,
                    policy.reasonCode,
                    "call_removed",
                    "final_route" to currentRoute(),
                    "endpoint_revision" to router.endpointRevision(),
                    "target_hfp_connected" to (address != null && hfp.known && address in hfp.connected),
                    "target_sco" to (address != null && hfp.known && address in hfp.audioConnected),
                    "post_confirmation_loss_observed" to postConfirmationLossObserved,
                    "observation_incomplete" to postConfirmationObservationIncomplete,
                )
            if (confirmation != null) {
                settings.recordLastSession(
                    policy.phase.name,
                    policy.reasonCode.name,
                    when {
                        postConfirmationLossObserved -> "TARGET_HFP_AUDIO_UNSTABLE"
                        postConfirmationObservationIncomplete -> "TARGET_HFP_AUDIO_OBSERVATION_INCOMPLETE"
                        else -> confirmation.name
                    },
                )
            }
            router.clearSession()
            policy = RoutingPolicy()
            lastTracePolicy = null
            lastTraceEvidence = null
            lastAudioFrameworkState = null
            sessionStarted = false
            manualSession = false
            manualCooldownUntil = 0L
            activeTransitionAt = null
            lateBindRecoveryDeadlineAt = null
            lastEndpointId = null
            confirmedAudioPresent = null
            postConfirmationWatchComplete = false
            postConfirmationLossObserved = false
            postConfirmationObservationIncomplete = false
            suspectedAudioLossAt = null
            suspectedAudioLossSampleAt = null
            RouterLog.event("SESSION_END", "No audio reset, disconnect, A2DP or projection operation performed")
        }
        queueEvaluation()
        super.onCallRemoved(call)
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        val previousRoute = currentRoute()
        val previousId = lastEndpointId
        router.updateCurrent(callEndpoint)
        lastEndpointId = callEndpoint.identifier.toString()
        val route = currentRoute()
        val protectedLateBindRoute =
            lateBindRecoveryDeadlineAt != null &&
                policy.phase == RoutingPolicy.Phase.IDLE &&
                isProtectedLateBindRoute(route)
        if (protectedLateBindRoute) {
            lateBindRecoveryDeadlineAt = null
            suspendSessionFromEvent(
                "Protected route observed during late-bind recovery; respecting possible user choice",
                RoutingPolicy.ReasonCode.ALREADY_ACTIVE_BIND,
            )
        } else {
            policy.observeRoute(route, SystemClock.elapsedRealtime())
            if (route != previousRoute) policy.observeStartupActivity(SystemClock.elapsedRealtime())
        }
        // Device names may contain personal data, so log only type + a salted ID.
        RouterLog.event("ENDPOINT", "type=${callEndpoint.endpointType}; id=${RouterLog.deviceId(callEndpoint.identifier.toString())}")
        trace.event(
            "ENDPOINT_CHANGED",
            "type" to callEndpoint.endpointType,
            "id" to RouterLog.deviceId(callEndpoint.identifier.toString()),
            "route" to route,
            "previous_route" to previousRoute,
            "previous_id" to RouterLog.deviceId(previousId),
            "endpoint_revision" to router.endpointRevision(),
            "phase" to policy.phase,
            "attempts" to policy.requests,
        )
        queueEvaluation()
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        router.updateAvailable(availableEndpoints)
        RouterLog.event(
            "AVAILABLE_ENDPOINTS",
            availableEndpoints.joinToString {
                "type=${it.endpointType},id=${RouterLog.deviceId(it.identifier.toString())}"
            },
        )
        trace.event(
            "ENDPOINT_SNAPSHOT",
            "count" to availableEndpoints.size,
            "bluetooth_count" to availableEndpoints.count { it.endpointType == CallEndpoint.TYPE_BLUETOOTH },
            "revision" to router.endpointRevision(),
            "phase" to policy.phase,
            "attempts" to policy.requests,
        )
        queueEvaluation()
    }

    /**
     * API 37 virtual callback. The exact public signature is enforced by the service harness until
     * the API 37 platform package is available to hosted sdkmanager builds.
     */
    @Suppress("unused")
    fun onCallEndpointRequested(callEndpoint: CallEndpoint) {
        val now = SystemClock.elapsedRealtime()
        val endpointId = callEndpoint.identifier.toString()
        val observation = router.observeRequest(callEndpoint)
        val matchesCurrent = endpointId == router.current()?.identifier?.toString()
        val matchesTarget = endpointId == targetEndpoint().endpoint?.identifier?.toString()
        val transitionAge = activeTransitionAt?.let { (now - it).coerceAtLeast(0) }
        val classification = observation.origin.name
        RouterLog.event(
            "ENDPOINT_REQUEST_OBSERVED",
            "type=${callEndpoint.endpointType}; classification=$classification; " +
                "request=${observation.requestId ?: "none"}; generation=${observation.generation}; " +
                "ageMs=${observation.ageMs ?: "none"}; pending=${observation.pendingCount}; " +
                "expired=${observation.expiredCount}; observational=true; " +
                "matchesCurrent=$matchesCurrent; matchesTarget=$matchesTarget; " +
                "id=${RouterLog.deviceId(endpointId)}",
        )
        trace.event(
            "ENDPOINT_REQUEST_OBSERVED",
            "type" to callEndpoint.endpointType,
            "id" to RouterLog.deviceId(endpointId),
            "classification" to classification,
            "request" to (observation.requestId ?: "none"),
            "generation" to observation.generation,
            "age_ms" to (observation.ageMs ?: "none"),
            "pending" to observation.pendingCount,
            "expired" to observation.expiredCount,
            "observational" to true,
            "matches_current" to matchesCurrent,
            "matches_target" to matchesTarget,
            "transition_age_ms" to (transitionAge ?: "none"),
            "phase" to policy.phase,
            "attempts" to policy.requests,
        )
        // Call-start requests extend only the short quiet period. Once our request has been sent,
        // another service's request suppresses selector recovery, without stopping HFP verification.
        if (observation.origin == AddressedTelecomRouter.RequestOrigin.EXTERNAL && sessionStarted) {
            policy.observeStartupActivity(now)
            if (policy.observeExternalRequestAfterTarget()) {
                trace.event(
                    "EXTERNAL_CONTROL_AFTER_TARGET",
                    "type" to callEndpoint.endpointType,
                    "id" to RouterLog.deviceId(endpointId),
                    "matches_target" to matchesTarget,
                    "selector_recovery_suppressed" to true,
                )
            }
        }
        queueEvaluation()
    }

    private fun guardHasActed(): Boolean =
        sessionStarted &&
            policy.phase in
            setOf(
                RoutingPolicy.Phase.WAITING,
                RoutingPolicy.Phase.VERIFYING,
                RoutingPolicy.Phase.STABILIZING,
            ) &&
            (policy.requests > 0 || policy.verified)

    /** Latch safety-relevant events before later callbacks can replace their state. */
    private fun suspendSessionFromEvent(
        reason: String,
        code: RoutingPolicy.ReasonCode,
    ) {
        if (disposed) return
        sessionStarted = true
        beginTrace("automatic", "safety_event")
        if (policy.phase == RoutingPolicy.Phase.SUSPENDED && policy.reasonCode == code) return
        policy.suspend(reason, code)
        cancelTick("session_suspended")
        RouterLog.event("SESSION_PAUSED_EVENT", reason)
        trace.event("SESSION_SUSPENDED", "reason" to code)
    }

    private fun queueEvaluation() {
        if (disposed) return
        handler.removeCallbacks(evaluateEvent)
        handler.post(evaluateEvent)
    }

    private fun beginTrace(
        mode: String,
        trigger: String,
    ) {
        val starting = !trace.isActive()
        trace.begin(mode, trigger)
        if (starting) {
            trace.event("SESSION_ENVIRONMENT", *ProcessDiagnostics.traceFields(this))
            lastTraceEvidence = null
            lastAudioFrameworkState = null
        }
    }

    private fun currentRoute(): RoutingPolicy.Route {
        val current = router.current() ?: return RoutingPolicy.Route.UNKNOWN
        return when (current.endpointType) {
            CallEndpoint.TYPE_SPEAKER -> RoutingPolicy.Route.SPEAKER
            CallEndpoint.TYPE_EARPIECE -> RoutingPolicy.Route.HANDSET
            CallEndpoint.TYPE_WIRED_HEADSET -> RoutingPolicy.Route.WIRED
            CallEndpoint.TYPE_STREAMING -> RoutingPolicy.Route.STREAMING
            CallEndpoint.TYPE_BLUETOOTH ->
                when {
                    !hfp.known -> RoutingPolicy.Route.UNKNOWN
                    router.isCurrent(targetEndpoint().endpoint) -> RoutingPolicy.Route.TARGET
                    router.isCurrent(competitorEndpoint().endpoint) -> RoutingPolicy.Route.COMPETING_DEVICE
                    else -> RoutingPolicy.Route.OTHER_BLUETOOTH
                }
            else -> RoutingPolicy.Route.UNKNOWN
        }
    }

    private fun targetEndpoint(): AddressedTelecomRouter.Target {
        val address = settings.targetAddress?.uppercase()
        return router.target(
            savedLabel = settings.targetName,
            targetHfpConnected = address != null && hfp.known && address in hfp.connected,
            connectedHfpCount = if (hfp.known) hfp.connected.size else 0,
        )
    }

    private fun competitorEndpoint(): AddressedTelecomRouter.Target {
        val address = settings.competitorAddress?.uppercase()
        return router.target(
            savedLabel = settings.competitorName,
            targetHfpConnected = address != null && hfp.known && address in hfp.connected,
            connectedHfpCount = if (hfp.known) hfp.connected.size else 0,
        )
    }

    private fun hfpAudioOwner(
        targetAddress: String?,
        competitorAddress: String?,
    ): String =
        when {
            !hfp.known -> "UNKNOWN"
            hfp.audioConnected.isEmpty() -> "NONE"
            hfp.audioConnected.size > 1 -> "MULTIPLE"
            targetAddress != null && targetAddress in hfp.audioConnected -> "TARGET"
            competitorAddress != null && competitorAddress in hfp.audioConnected -> "COMPETITOR"
            else -> "OTHER"
        }

    private fun liveCalls(): List<Call> =
        records.keys.filter {
            it.details.state != Call.STATE_DISCONNECTED && it.details.state != Call.STATE_DISCONNECTING
        }

    private fun evaluate() {
        if (disposed) return
        val previousTickAt = scheduledTickAt
        cancelTick("new_evaluation")
        val now = SystemClock.elapsedRealtime()
        val hfpFresh = hfp.sampledAt?.let { now - it in 0..RoutingPolicy.MAX_HFP_SAMPLE_AGE_MS } == true
        val authorized = Access.ongoingCalls(this)
        val runtimeGranted = Access.runtimeGranted(this)
        val live = liveCalls()
        val active = live.any { it.details.state == Call.STATE_ACTIVE }
        val rejections = live.mapNotNull(classifier::rejection)
        val safe = runtimeGranted && live.isNotEmpty() && rejections.isEmpty()
        lastSafety =
            when {
                !runtimeGranted -> "Required runtime permissions not granted"
                rejections.isNotEmpty() -> rejections.joinToString("; ")
                live.isEmpty() -> "No live calls"
                else -> "SIM-backed call and emergency-number checks passed"
            }
        val address = settings.targetAddress?.uppercase()
        val hfpConnected =
            when {
                address == null -> false
                !hfp.known || !hfpFresh -> null
                else -> address in hfp.connected
            }
        val target = targetEndpoint()
        val competitor = competitorEndpoint()
        val endpointAvailable = if (router.hasAvailableSnapshot()) target.endpoint != null else null
        val targetHfpAudio =
            when {
                address == null -> false
                !hfp.known || !hfpFresh -> null
                else -> address in hfp.audioConnected
            }
        val competitorAddress = settings.competitorAddress?.uppercase()
        val selectorRecoveryAvailable =
            when {
                competitorAddress == null -> false
                !hfp.known || !hfpFresh -> null
                else -> competitorAddress in hfp.audioConnected && competitor.endpoint != null
            }
        val audioState = if (active) audioFramework.sample(now) else null
        if (audioState != null && audioState != lastAudioFrameworkState) {
            lastAudioFrameworkState = audioState
            trace.event(
                "AUDIO_FRAMEWORK_STATE",
                "mode" to audioState.mode,
                "communication_device" to audioState.communicationDevice,
                "diagnostic_only" to true,
            )
        }
        val route = currentRoute()
        val lateBindDeadline = lateBindRecoveryDeadlineAt
        if (lateBindDeadline != null && policy.phase == RoutingPolicy.Phase.IDLE) {
            val prerequisitesReady =
                settings.enabled &&
                    authorized &&
                    active &&
                    live.size == 1 &&
                    records.size == 1 &&
                    safe &&
                    projection == true &&
                    hfpConnected == true &&
                    endpointAvailable == true
            val carOwnedRoute =
                route in
                    setOf(
                        RoutingPolicy.Route.TARGET,
                        RoutingPolicy.Route.COMPETING_DEVICE,
                        RoutingPolicy.Route.STREAMING,
                    )
            val userOwnedRoute =
                isProtectedLateBindRoute(route)
            val immediatelyIneligible =
                !settings.enabled ||
                    !authorized ||
                    !active ||
                    live.size != 1 ||
                    records.size != 1 ||
                    !safe
            when {
                immediatelyIneligible -> {
                    lateBindRecoveryDeadlineAt = null
                    policy.begin(now, route)
                }
                userOwnedRoute || projection == false -> {
                    lateBindRecoveryDeadlineAt = null
                    policy.suspend(
                        "Already-active call is not on a verified Android Auto/car route; automatic takeover suppressed",
                        RoutingPolicy.ReasonCode.ALREADY_ACTIVE_BIND,
                    )
                    trace.event("SESSION_SUSPENDED", "reason" to policy.reasonCode, "route" to route)
                }
                prerequisitesReady && carOwnedRoute -> {
                    lateBindRecoveryDeadlineAt = null
                    policy.begin(now, route)
                    trace.event("LATE_BIND_RECOVERY_STARTED", "initial_route" to route)
                    RouterLog.event("LATE_BIND_RECOVERY_STARTED", "Verified Android Auto/car route; automatic startup guard enabled")
                }
                now >= lateBindDeadline -> {
                    lateBindRecoveryDeadlineAt = null
                    policy.suspend(
                        "Already-active call lacked complete safe recovery evidence",
                        RoutingPolicy.ReasonCode.ALREADY_ACTIVE_BIND,
                    )
                    trace.event("SESSION_SUSPENDED", "reason" to policy.reasonCode, "route" to route)
                }
                else -> {
                    val state =
                        "Service: bound\n" +
                            "Projection: ${projection ?: "unknown"}\n" +
                            "Call: ACTIVE; live=${live.size}\n" +
                            "Safety: $lastSafety\n" +
                            "Selected device: endpoint resolved=${target.endpoint != null}; " +
                            "HFP connected=${hfpConnected ?: "unknown"}\n" +
                            "Telecom endpoint: $route\n" +
                            "HFP audio: ${hfpAudioOwner(address, settings.competitorAddress?.uppercase())}\n" +
                            "Controller: WAITING; reason=LATE_BIND_RECOVERY_EVIDENCE"
                    if (state != lastPublished) {
                        lastPublished = state
                        RouterLog.event("STATUS", state.replace("\n", " | "))
                        SessionBridge.publish(state)
                    }
                    scheduleTick(lateBindDeadline, "late_bind_evidence")
                    return
                }
            }
        }
        val decision =
            policy.evaluate(
                RoutingPolicy.Snapshot(
                    now = now,
                    enabled = settings.enabled,
                    authorized = authorized,
                    active = active,
                    singleCall = live.size == 1 && records.size == 1,
                    safeCellularCall = safe,
                    projection = projection,
                    targetHfpConnected = hfpConnected,
                    targetHfpAudio = targetHfpAudio,
                    targetHfpSampleAt = hfp.sampledAt,
                    selectorRecoveryAvailable = selectorRecoveryAvailable,
                    targetAvailable = endpointAvailable,
                    endpointRevision = router.endpointRevision(),
                    route = route,
                ),
            )
        if (active && policy.verified) {
            if (!postConfirmationWatchComplete && postConfirmationDeadlineAt == null && hfpStarted) {
                postConfirmationDeadlineAt = now + POST_CONFIRMATION_WATCH_MS
                trace.event("POST_CONFIRMATION_WATCH_STARTED", "duration_ms" to POST_CONFIRMATION_WATCH_MS)
            }
            val prior = confirmedAudioPresent
            if (prior != null && prior != targetHfpAudio) {
                trace.event(
                    "CONFIRMED_AUDIO_CHANGED",
                    "target_sco" to targetHfpAudio,
                    "hfp_audio_owner" to hfpAudioOwner(address, competitorAddress),
                    "telecom_route" to route,
                    "previous_target_sco" to prior,
                )
            }
            confirmedAudioPresent = targetHfpAudio
            if (postConfirmationDeadlineAt != null && targetHfpAudio == null) postConfirmationObservationIncomplete = true
            // A shutdown sample often precedes DISCONNECTED. Require two fresh negative
            // observations separated by a grace interval before calling audio unstable.
            if (targetHfpAudio == false && hfp.audioConnected.isNotEmpty() && audioState?.mode != "NORMAL") {
                val since =
                    suspectedAudioLossAt ?: now.also {
                        suspectedAudioLossAt = it
                        suspectedAudioLossSampleAt = hfp.sampledAt
                    }
                if (hfpFresh && now - since >= 500 && hfp.sampledAt != suspectedAudioLossSampleAt) postConfirmationLossObserved = true
            } else {
                suspectedAudioLossAt = null
                suspectedAudioLossSampleAt = null
            }
            if (postConfirmationDeadlineAt?.let { now >= it } == true) {
                finishPostConfirmationWatch("deadline")
            }
        }
        val evidence =
            listOf<Pair<String, Any?>>(
                "enabled" to settings.enabled,
                "authorized" to authorized,
                "runtime_granted" to runtimeGranted,
                "manual" to manualSession,
                "active" to active,
                "live_calls" to live.size,
                "records" to records.size,
                "safe_cellular" to safe,
                "projection" to projection,
                "hfp_monitoring" to hfpStarted,
                "hfp_fresh" to hfpFresh,
                "hfp_known" to hfp.known,
                "hfp_connected_count" to hfp.connected.size,
                "hfp_audio_count" to hfp.audioConnected.size,
                "hfp_audio_owner" to hfpAudioOwner(address, competitorAddress),
                "audio_mode" to audioState?.mode,
                "communication_device" to audioState?.communicationDevice,
                "hfp_sample" to hfp.sampleSequence,
                "hfp_sample_age_ms" to hfp.sampledAt?.let { (now - it).coerceAtLeast(0) },
                "hfp_audio_device" to hfp.audioConnected.singleOrNull()?.let(RouterLog::deviceId),
                "target_configured" to (address != null),
                "target_hfp_connected" to hfpConnected,
                "target_sco" to targetHfpAudio,
                "competitor_configured" to (competitorAddress != null),
                "competitor_hfp_connected" to
                    if (hfp.known && competitorAddress != null) competitorAddress in hfp.connected else null,
                "competitor_sco" to
                    if (hfp.known && competitorAddress != null) competitorAddress in hfp.audioConnected else null,
                "endpoint_snapshot" to router.hasAvailableSnapshot(),
                "endpoint_revision" to router.endpointRevision(),
                "target_available" to endpointAvailable,
                "target_resolution" to (target.basis ?: "UNRESOLVED"),
                "competitor_available" to (competitor.endpoint != null),
                "competitor_resolution" to (competitor.basis ?: "UNRESOLVED"),
                "route" to route,
                "phase" to policy.phase,
                "reason" to policy.reasonCode,
                "requests" to policy.requests,
                "selector_recoveries" to policy.selectorRecoveries,
                "request_target" to decision.requestTarget,
                "restore_selector" to decision.restoreSelector,
                "wake_in_ms" to decision.wakeAt?.let { (it - now).coerceAtLeast(0) },
            )
        val evidenceKey = evidence.joinToString("|") { (key, value) -> "$key=$value" }
        if (trace.isActive() && evidenceKey != lastTraceEvidence) {
            lastTraceEvidence = evidenceKey
            trace.event("EVIDENCE_SNAPSHOT", *evidence.toTypedArray())
        }
        val policyState = policy.phase to policy.reasonCode
        if (policyState != lastTracePolicy) {
            lastTracePolicy = policyState
            trace.event(
                "POLICY_STATE",
                "phase" to policy.phase,
                "reason" to policy.reasonCode,
                "attempts" to policy.requests,
            )
            when (policy.reasonCode) {
                RoutingPolicy.ReasonCode.SELECTOR_RECOVERY_CONFIRMED ->
                    trace.event(
                        "SELECTOR_RECOVERY_CONFIRMED",
                        "route" to route,
                        "hfp_audio_owner" to hfpAudioOwner(address, competitorAddress),
                        "target_sco" to targetHfpAudio,
                    )
                RoutingPolicy.ReasonCode.SELECTOR_RECOVERY_NOT_CONFIRMED,
                RoutingPolicy.ReasonCode.SELECTOR_RECOVERY_FAILED,
                ->
                    trace.event(
                        "SELECTOR_RECOVERY_TERMINATED",
                        "reason" to policy.reasonCode,
                        "route" to route,
                        "hfp_audio_owner" to hfpAudioOwner(address, competitorAddress),
                    )
                else -> Unit
            }
        }
        if (decision.requestTarget && target.endpoint != null) {
            val attempt = requireNotNull(decision.requestAttempt)
            val targetId = RouterLog.deviceId(address)
            val requestMode = if (manualSession) "manual" else "auto"
            try {
                RouterLog.event(
                    "ROUTE_REQUEST",
                    "target=$targetId; attempt=${policy.requests}; mode=$requestMode; " +
                        "backend=Telecom.requestCallEndpointChange; basis=${target.basis}",
                )
                trace.event(
                    "REQUEST_SUBMITTED",
                    "attempt" to policy.requests,
                    "mode" to if (manualSession) "manual" else "automatic",
                    "basis" to target.basis,
                    "target" to RouterLog.deviceId(address),
                )
                router.request(
                    target.endpoint,
                    started = { ticket ->
                        RouterLog.event(
                            "ROUTE_REQUEST_CONTEXT",
                            "attempt=$attempt; request=${ticket.id}; generation=${ticket.generation}; " +
                                "endpointRevision=${router.endpointRevision()}; preRoute=${currentRoute()}",
                        )
                        trace.event(
                            "REQUEST_CONTEXT",
                            "attempt" to attempt,
                            "request" to ticket.id,
                            "generation" to ticket.generation,
                            "endpoint_revision" to router.endpointRevision(),
                            "pre_route" to currentRoute(),
                        )
                    },
                    accepted = { ticket ->
                        if (!router.isCurrentGeneration(ticket)) {
                            RouterLog.event(
                                "ROUTE_RESULT_IGNORED",
                                "request=${ticket.id}; generation=${ticket.generation}; current=${router.generation()}; result=accepted",
                            )
                            trace.event(
                                "REQUEST_RESULT_IGNORED",
                                "request" to ticket.id,
                                "generation" to ticket.generation,
                                "result" to "accepted",
                            )
                            return@request
                        }
                        policy.requestSucceeded(attempt, SystemClock.elapsedRealtime())
                        val latency = (SystemClock.elapsedRealtime() - ticket.createdAt).coerceAtLeast(0)
                        RouterLog.event(
                            "ROUTE_ACCEPTED",
                            "attempt=$attempt; request=${ticket.id}; generation=${ticket.generation}; latencyMs=$latency; " +
                                "Telecom completed the request; awaiting endpoint observation",
                        )
                        trace.event(
                            "REQUEST_ACCEPTED",
                            "attempt" to attempt,
                            "request" to ticket.id,
                            "generation" to ticket.generation,
                            "latency_ms" to latency,
                        )
                        queueEvaluation()
                    },
                    rejected = { ticket, error ->
                        if (!router.isCurrentGeneration(ticket)) {
                            RouterLog.event(
                                "ROUTE_RESULT_IGNORED",
                                "request=${ticket.id}; generation=${ticket.generation}; current=${router.generation()}; " +
                                    "result=rejected; code=${error.code}",
                            )
                            trace.event(
                                "REQUEST_RESULT_IGNORED",
                                "request" to ticket.id,
                                "generation" to ticket.generation,
                                "result" to "rejected",
                                "code" to error.code,
                            )
                            return@request
                        }
                        val mapped =
                            when (error.code) {
                                CallEndpointException.ERROR_REQUEST_TIME_OUT -> RoutingPolicy.RequestError.TIMEOUT
                                CallEndpointException.ERROR_ENDPOINT_DOES_NOT_EXIST -> RoutingPolicy.RequestError.ENDPOINT_GONE
                                CallEndpointException.ERROR_ANOTHER_REQUEST -> RoutingPolicy.RequestError.CANCELLED_BY_OTHER
                                else -> RoutingPolicy.RequestError.UNSPECIFIED
                            }
                        policy.requestFailed(attempt, mapped)
                        val latency = (SystemClock.elapsedRealtime() - ticket.createdAt).coerceAtLeast(0)
                        RouterLog.event(
                            "ROUTE_REJECTED",
                            "attempt=$attempt; request=${ticket.id}; generation=${ticket.generation}; latencyMs=$latency; " +
                                "code=${error.code}; class=$mapped",
                        )
                        trace.event(
                            "REQUEST_REJECTED",
                            "attempt" to attempt,
                            "request" to ticket.id,
                            "generation" to ticket.generation,
                            "latency_ms" to latency,
                            "code" to error.code,
                            "class" to mapped,
                        )
                        queueEvaluation()
                    },
                )
            } catch (e: RuntimeException) {
                policy.requestFailed(attempt, RoutingPolicy.RequestError.RUNTIME_EXCEPTION)
                RouterLog.event("ROUTE_ERROR", "attempt=$attempt; type=${e.javaClass.simpleName}")
                trace.event("REQUEST_ERROR", "attempt" to attempt, "type" to e.javaClass.simpleName)
            }
        }
        if (decision.restoreSelector && competitor.endpoint != null) {
            val competitorId = RouterLog.deviceId(competitorAddress)
            try {
                RouterLog.event(
                    "SELECTOR_RECOVERY_REQUEST",
                    "target=$competitorId; backend=Telecom.requestCallEndpointChange; basis=${competitor.basis}",
                )
                trace.event(
                    "SELECTOR_RECOVERY_SUBMITTED",
                    "target" to competitorId,
                    "basis" to competitor.basis,
                    "displayed_route" to route,
                    "target_sco" to targetHfpAudio,
                    "competitor_sco" to true,
                    "hfp_audio_owner" to hfpAudioOwner(address, competitorAddress),
                    "endpoint_revision" to router.endpointRevision(),
                )
                router.request(
                    competitor.endpoint,
                    started = { ticket ->
                        trace.event(
                            "SELECTOR_RECOVERY_CONTEXT",
                            "request" to ticket.id,
                            "generation" to ticket.generation,
                            "endpoint_revision" to router.endpointRevision(),
                            "pre_route" to currentRoute(),
                            "hfp_audio_owner" to hfpAudioOwner(address, competitorAddress),
                        )
                    },
                    accepted = { ticket ->
                        val latency = (SystemClock.elapsedRealtime() - ticket.createdAt).coerceAtLeast(0)
                        if (!router.isCurrentGeneration(ticket)) {
                            trace.event(
                                "SELECTOR_RECOVERY_RESULT_IGNORED",
                                "request" to ticket.id,
                                "generation" to ticket.generation,
                                "current_generation" to router.generation(),
                                "result" to "accepted",
                                "latency_ms" to latency,
                            )
                            return@request
                        }
                        policy.selectorRecoverySucceeded()
                        RouterLog.event(
                            "SELECTOR_RECOVERY_ACCEPTED",
                            "request=${ticket.id}; generation=${ticket.generation}; latencyMs=$latency; " +
                                "route=${currentRoute()}",
                        )
                        trace.event(
                            "SELECTOR_RECOVERY_ACCEPTED",
                            "request" to ticket.id,
                            "generation" to ticket.generation,
                            "latency_ms" to latency,
                            "route" to currentRoute(),
                            "hfp_audio_owner" to hfpAudioOwner(address, competitorAddress),
                        )
                        queueEvaluation()
                    },
                    rejected = { ticket, error ->
                        val latency = (SystemClock.elapsedRealtime() - ticket.createdAt).coerceAtLeast(0)
                        if (!router.isCurrentGeneration(ticket)) {
                            trace.event(
                                "SELECTOR_RECOVERY_RESULT_IGNORED",
                                "request" to ticket.id,
                                "generation" to ticket.generation,
                                "current_generation" to router.generation(),
                                "result" to "rejected",
                                "latency_ms" to latency,
                                "code" to error.code,
                            )
                            return@request
                        }
                        policy.selectorRecoveryFailed()
                        RouterLog.event(
                            "SELECTOR_RECOVERY_REJECTED",
                            "request=${ticket.id}; generation=${ticket.generation}; latencyMs=$latency; code=${error.code}",
                        )
                        trace.event(
                            "SELECTOR_RECOVERY_REJECTED",
                            "request" to ticket.id,
                            "generation" to ticket.generation,
                            "latency_ms" to latency,
                            "code" to error.code,
                            "route" to currentRoute(),
                            "hfp_audio_owner" to hfpAudioOwner(address, competitorAddress),
                        )
                        queueEvaluation()
                    },
                )
            } catch (e: RuntimeException) {
                policy.selectorRecoveryFailed()
                RouterLog.event("SELECTOR_RECOVERY_ERROR", "type=${e.javaClass.simpleName}")
                trace.event("SELECTOR_RECOVERY_ERROR", "type" to e.javaClass.simpleName)
            }
        }
        if (route == RoutingPolicy.Route.TARGET) trace.confirmTelecom()
        if (policy.verified) trace.confirmTargetHfpAudio()
        val state =
            listOf(
                "Service: bound",
                "Projection: ${projection ?: "unknown"}",
                "Call: ${if (active) "ACTIVE" else "not active"}; live=${live.size}",
                "Safety: $lastSafety",
                "Selected device: endpoint resolved=${target.endpoint != null}; " +
                    "HFP connected=${hfpConnected ?: "unknown"}; SCO=${targetHfpAudio ?: "unknown"}",
                "Endpoint resolution: ${target.reason}",
                "Telecom endpoint: $route",
                "HFP audio: ${if (hfpFresh) {
                    hfpAudioOwner(
                        address,
                        competitorAddress,
                    )
                } else {
                    "UNKNOWN"
                }}; BMW confirmed=${policy.verified && targetHfpAudio == true}",
                "Selector recovery: ${when {
                    competitorAddress == null -> "unavailable: competing device not configured"
                    competitor.endpoint == null -> "unavailable: competing endpoint unresolved"
                    !hfpFresh -> "waiting for fresh HFP evidence"
                    selectorRecoveryAvailable == true -> "ready: competing device owns audio"
                    else -> "not applicable to current audio owner"
                }}",
                "Audio framework: mode=${audioState?.mode ?: "unknown"}; " +
                    "device=${audioState?.communicationDevice ?: "unknown"} (diagnostic only)",
                "Controller: ${policy.phase}; attempts=${policy.requests}; " +
                    "selectorRecoveries=${policy.selectorRecoveries}; reason=${policy.reasonCode}",
                if (policy.verified) {
                    when (targetHfpAudio) {
                        true -> "Target HFP audio observed now; automatic routing released"
                        false -> "Target HFP audio is no longer observed; automatic routing remains released"
                        null -> "Target HFP audio evidence is stale or unavailable; confirmed only at call start"
                    }
                } else {
                    policy.reason
                },
            ).joinToString("\n")
        if (state != lastPublished) {
            lastPublished = state
            RouterLog.event("STATUS", state.replace("\n", " | "))
            SessionBridge.publish(state)
        }
        if (policy.phase !in setOf(RoutingPolicy.Phase.SUSPENDED, RoutingPolicy.Phase.FAILED)) {
            decision.wakeAt?.let { deadline ->
                val sampleHfp =
                    policy.phase in setOf(RoutingPolicy.Phase.WAITING, RoutingPolicy.Phase.VERIFYING, RoutingPolicy.Phase.STABILIZING)
                val sampleProjection = !manualSession && policy.phase == RoutingPolicy.Phase.WAITING && projection != true
                val desired = if ((sampleHfp && hfpStarted) || sampleProjection) minOf(deadline, now + HFP_VERIFY_POLL_MS) else deadline
                val due = minOf(desired, previousTickAt?.takeIf { it > now } ?: desired)
                scheduleTick(due, if (due < deadline) "HFP_SAMPLE_POLL" else policy.reasonCode.name)
            }
            if (policy.phase == RoutingPolicy.Phase.RELEASED && hfpStarted) {
                postConfirmationDeadlineAt?.let { deadline ->
                    val desired = minOf(deadline, now + HFP_VERIFY_POLL_MS)
                    scheduleTick(minOf(desired, previousTickAt?.takeIf { it > now } ?: desired), "POST_CONFIRMATION_POLL")
                }
            }
        }
    }

    private fun finishPostConfirmationWatch(reason: String) {
        val deadline = postConfirmationDeadlineAt ?: return
        postConfirmationDeadlineAt = null
        postConfirmationWatchComplete = true
        trace.event(
            "POST_CONFIRMATION_WATCH_FINISHED",
            "reason" to reason,
            "deadline_elapsed_ms" to deadline,
            "loss_observed" to postConfirmationLossObserved,
            "final_target_sco" to confirmedAudioPresent,
        )
    }

    override fun routeNow() {
        val now = SystemClock.elapsedRealtime()
        if (now < manualCooldownUntil) {
            RouterLog.event("MANUAL_TEST", "A request is already within its verification window")
            return
        }
        ensureHfpMonitoring("manual_route_now")
        // This explicit one-shot bypasses only auto toggle/projection, NOT safety/identity checks.
        sessionStarted = true
        manualSession = true
        beginTrace("manual", "route_now")
        policy.begin(now, currentRoute(), manualOneShot = true)
        confirmedAudioPresent = null
        postConfirmationDeadlineAt = null
        postConfirmationWatchComplete = false
        postConfirmationLossObserved = false
        postConfirmationObservationIncomplete = false
        suspectedAudioLossAt = null
        suspectedAudioLossSampleAt = null
        manualCooldownUntil = now + 1_500
        RouterLog.event("MANUAL_TEST", "One request only; projection gate bypassed explicitly")
        evaluate()
    }

    private fun isProtectedLateBindRoute(route: RoutingPolicy.Route): Boolean =
        route in DEFINITE_USER_OWNED_ROUTES ||
            // Before the target endpoint snapshot arrives, a configured car Bluetooth endpoint
            // is indistinguishable from OTHER_BLUETOOTH. Protect it only once the exact target is
            // resolvable; until then the bounded evidence window remains passive.
            (route == RoutingPolicy.Route.OTHER_BLUETOOTH && targetEndpoint().endpoint != null)

    override fun pauseSession() {
        sessionStarted = true
        beginTrace("manual", "pause")
        policy.suspend("Paused by user for this call session", RoutingPolicy.ReasonCode.USER_PAUSED)
        cancelTick("user_pause")
        RouterLog.event("USER_PAUSE", "No further requests this session; an already-submitted request cannot be recalled")
        trace.event("SESSION_SUSPENDED", "reason" to policy.reasonCode)
        evaluate()
    }

    override fun onUnbind(intent: Intent): Boolean {
        RouterLog.event("TELECOM_UNBOUND", "System unbound service; ${ProcessDiagnostics.snapshot(this)}")
        stopObservers()
        return super.onUnbind(intent)
    }

    private fun stopObservers() {
        if (disposed) return
        disposed = true
        cancelTick("service_stopped")
        finishPostConfirmationWatch("service_stopped")
        trace.finish(policy.phase, policy.reasonCode, "service_stopped")
        router.clearSession()
        handler.removeCallbacksAndMessages(null)
        records.forEach { (call, record) -> call.unregisterCallback(record.callback) }
        records.clear()
        projectionMonitor.close()
        audioFramework.close()
        hfp.close()
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (SessionBridge.controller?.get() === this) SessionBridge.controller = null
        SessionBridge.publish("No Telecom service bound. This is normal between calls.")
    }

    override fun onDestroy() {
        RouterLog.event("SERVICE_DESTROY", ProcessDiagnostics.snapshot(this))
        stopObservers()
        super.onDestroy()
    }

    override fun dump(
        fd: FileDescriptor,
        writer: PrintWriter,
        args: Array<out String>,
    ) {
        writer.println("Car Call Router (redacted)")
        writer.println(SessionBridge.status)
        writer.println(RouterLog.recentText())
    }

    companion object {
        private const val HFP_VERIFY_POLL_MS = 250L
        private const val POST_CONFIRMATION_WATCH_MS = 3_000L
        private const val LATE_BIND_EVIDENCE_WINDOW_MS = 5_000L
        private val DEFINITE_USER_OWNED_ROUTES =
            setOf(
                RoutingPolicy.Route.HANDSET,
                RoutingPolicy.Route.SPEAKER,
                RoutingPolicy.Route.WIRED,
            )

        private fun isPreActive(state: Int) =
            state in setOf(Call.STATE_NEW, Call.STATE_RINGING, Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT)

        private fun stateName(state: Int) =
            when (state) {
                Call.STATE_NEW -> "NEW"
                Call.STATE_RINGING -> "RINGING"
                Call.STATE_DIALING -> "DIALING"
                Call.STATE_CONNECTING -> "CONNECTING"
                Call.STATE_ACTIVE -> "ACTIVE"
                Call.STATE_HOLDING -> "HOLDING"
                Call.STATE_DISCONNECTED -> "DISCONNECTED"
                Call.STATE_DISCONNECTING -> "DISCONNECTING"
                Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
                else -> "STATE_$state"
            }
    }
}
