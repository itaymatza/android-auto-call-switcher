package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.RoutingPolicy.Phase
import org.carcallrouter.companion.core.RoutingPolicy.ReasonCode
import org.carcallrouter.companion.core.RoutingPolicy.RequestError
import org.carcallrouter.companion.core.RoutingPolicy.Route
import org.carcallrouter.companion.core.RoutingPolicy.Snapshot

private fun snapshot(
    now: Long,
    enabled: Boolean = true,
    authorized: Boolean = true,
    active: Boolean = true,
    singleCall: Boolean = true,
    safeCellularCall: Boolean = true,
    projection: Boolean? = true,
    targetHfpConnected: Boolean? = true,
    targetHfpAudio: Boolean? = false,
    selectorRecoveryAvailable: Boolean? = false,
    targetAvailable: Boolean? = true,
    route: Route = Route.COMPETING_DEVICE,
) = Snapshot(
    now = now,
    enabled = enabled,
    authorized = authorized,
    active = active,
    singleCall = singleCall,
    safeCellularCall = safeCellularCall,
    projection = projection,
    targetHfpConnected = targetHfpConnected,
    targetHfpAudio = targetHfpAudio,
    targetHfpSampleAt = now,
    selectorRecoveryAvailable = selectorRecoveryAvailable,
    targetAvailable = targetAvailable,
    endpointRevision = 1,
    route = route,
)

private fun policy(
    settleDelayMs: Long = 0,
    actionWindowMs: Long = 4_000,
    stableMs: Long = 250,
    manual: Boolean = false,
) = RoutingPolicy(
    settleDelayMs = settleDelayMs,
    actionWindowMs = actionWindowMs,
    targetAudioStableMs = stableMs,
).also { it.begin(0, Route.COMPETING_DEVICE, manual) }

/** Named deterministic checks for the one-shot routing transaction. */
object PolicyCases {
    fun cases(): List<Pair<String, () -> Unit>> =
        listOf(
            "idle is passive" to { check(!RoutingPolicy().evaluate(snapshot(0)).requestTarget) },
            "automatic call waits for Android Auto to settle" to {
                val p = policy(settleDelayMs = 500)
                val waiting = p.evaluate(snapshot(0))
                check(!waiting.requestTarget)
                check(waiting.wakeAt == 500L)
                check(p.evaluate(snapshot(500)).requestTarget)
            },
            "displayed BMW with Android Auto SCO still forces request" to {
                val p = policy()
                val decision = p.evaluate(snapshot(0, route = Route.TARGET, targetHfpAudio = false))
                check(decision.requestTarget)
                check(p.requests == 1)
            },
            "only actual BMW HFP audio can verify success" to {
                val p = policy(stableMs = 100)
                p.evaluate(snapshot(0, route = Route.TARGET, targetHfpAudio = false))
                p.evaluate(snapshot(10, route = Route.TARGET, targetHfpAudio = true))
                check(!p.verified)
                p.evaluate(snapshot(110, route = Route.TARGET, targetHfpAudio = true))
                check(p.verified)
                check(p.phase == Phase.RELEASED)
                check(p.reasonCode == ReasonCode.TARGET_AUDIO_CONFIRMED)
            },
            "transient BMW SCO does not verify" to {
                val p = policy(stableMs = 100)
                p.evaluate(snapshot(0))
                p.evaluate(snapshot(10, targetHfpAudio = true))
                p.evaluate(snapshot(50, targetHfpAudio = false))
                p.evaluate(snapshot(110, targetHfpAudio = true))
                check(!p.verified)
                p.evaluate(snapshot(210, targetHfpAudio = true))
                check(p.verified)
            },
            "one-shot transaction never sends a second request" to {
                val p = policy(actionWindowMs = 4_000)
                check(p.evaluate(snapshot(0)).requestTarget)
                check(!p.evaluate(snapshot(2_500)).requestTarget)
                check(!p.evaluate(snapshot(3_000)).requestTarget)
                p.evaluate(snapshot(4_000))
                check(p.requests == 1)
                check(p.phase == Phase.FAILED)
                check(p.reasonCode == ReasonCode.TARGET_AUDIO_NOT_CONFIRMED)
            },
            "split-brain failure restores the manual BMW selector once" to {
                val p = policy(actionWindowMs = 4_000)
                check(p.evaluate(snapshot(0, route = Route.TARGET)).requestTarget)
                val recovery =
                    p.evaluate(
                        snapshot(
                            4_000,
                            route = Route.TARGET,
                            targetHfpAudio = false,
                            selectorRecoveryAvailable = true,
                        ),
                    )
                check(recovery.restoreSelector)
                check(!recovery.requestTarget)
                check(p.requests == 1)
                check(p.selectorRecoveries == 1)
                p.selectorRecoverySucceeded()
                check(p.reasonCode == ReasonCode.SELECTOR_RECOVERY_ACCEPTED)
                p.evaluate(snapshot(4_100, route = Route.COMPETING_DEVICE, selectorRecoveryAvailable = true))
                check(p.phase == Phase.FAILED)
                check(p.reasonCode == ReasonCode.SELECTOR_RECOVERY_CONFIRMED)
            },
            "selector recovery never guesses or loops" to {
                listOf(
                    snapshot(4_000, route = Route.COMPETING_DEVICE, selectorRecoveryAvailable = true),
                    snapshot(4_000, route = Route.TARGET, selectorRecoveryAvailable = false),
                    snapshot(4_000, route = Route.TARGET, selectorRecoveryAvailable = null),
                ).forEach { finalSnapshot ->
                    val p = policy(actionWindowMs = 4_000)
                    p.evaluate(snapshot(0))
                    check(!p.evaluate(finalSnapshot).restoreSelector)
                    check(p.phase == Phase.FAILED)
                    check(p.selectorRecoveries == 0)
                }

                val timeout = policy(actionWindowMs = 4_000)
                timeout.evaluate(snapshot(0, route = Route.TARGET))
                check(
                    timeout.evaluate(snapshot(4_000, route = Route.TARGET, selectorRecoveryAvailable = true)).restoreSelector,
                )
                check(!timeout.evaluate(snapshot(6_500, route = Route.TARGET, selectorRecoveryAvailable = true)).restoreSelector)
                check(timeout.reasonCode == ReasonCode.SELECTOR_RECOVERY_NOT_CONFIRMED)
                check(timeout.selectorRecoveries == 1)

                val rejected = policy(actionWindowMs = 4_000)
                rejected.evaluate(snapshot(0, route = Route.TARGET))
                rejected.evaluate(snapshot(4_000, route = Route.TARGET, selectorRecoveryAvailable = true))
                rejected.selectorRecoveryFailed()
                check(rejected.reasonCode == ReasonCode.SELECTOR_RECOVERY_FAILED)
                check(rejected.selectorRecoveries == 1)
            },
            "selector recovery callback races and accepted timeout are bounded" to {
                val outsideRecovery = policy()
                outsideRecovery.selectorRecoverySucceeded()
                outsideRecovery.selectorRecoveryFailed()
                check(outsideRecovery.phase == Phase.WAITING)

                val accepted = policy(actionWindowMs = 4_000)
                accepted.evaluate(snapshot(0, route = Route.TARGET))
                val recovery =
                    accepted.evaluate(
                        snapshot(
                            4_000,
                            route = Route.TARGET,
                            selectorRecoveryAvailable = true,
                        ),
                    )
                accepted.selectorRecoverySucceeded()
                val pending = accepted.evaluate(snapshot(5_000, route = Route.TARGET))
                check(accepted.phase == Phase.RECOVERING_SELECTOR)
                check(pending.wakeAt == recovery.wakeAt)
                accepted.evaluate(snapshot(requireNotNull(recovery.wakeAt), route = Route.TARGET))
                check(accepted.phase == Phase.FAILED)
                check(accepted.reasonCode == ReasonCode.SELECTOR_RECOVERY_NOT_CONFIRMED)
                check(accepted.reason.contains("accepted"))
            },
            "protected user routes stop selector recovery" to {
                listOf(Route.SPEAKER, Route.HANDSET, Route.WIRED, Route.OTHER_BLUETOOTH).forEach { route ->
                    val p = policy(actionWindowMs = 4_000)
                    p.evaluate(snapshot(0, route = Route.TARGET))
                    val recovery =
                        p.evaluate(
                            snapshot(4_000, route = Route.TARGET, selectorRecoveryAvailable = true),
                        )
                    check(recovery.restoreSelector)
                    p.selectorRecoverySucceeded()
                    val decision = p.evaluate(snapshot(4_100, route = route))
                    check(!decision.requestTarget)
                    check(!decision.restoreSelector)
                    check(p.phase == Phase.SUSPENDED)
                    check(p.reasonCode == ReasonCode.USER_OVERRIDE)
                    check(p.requests == 1)
                    check(p.selectorRecoveries == 1)
                }
            },
            "accepted Telecom request still needs BMW SCO" to {
                val p = policy(actionWindowMs = 1_000)
                val request = p.evaluate(snapshot(0))
                p.requestSucceeded(requireNotNull(request.requestAttempt), 100)
                check(!p.evaluate(snapshot(500, route = Route.TARGET)).requestTarget)
                p.evaluate(snapshot(1_000, route = Route.TARGET))
                check(p.phase == Phase.FAILED)
            },
            "Telecom timeout waits for audio without retry" to {
                val p = policy(actionWindowMs = 1_000)
                val request = p.evaluate(snapshot(0))
                p.requestFailed(requireNotNull(request.requestAttempt), RequestError.TIMEOUT)
                check(p.reasonCode == ReasonCode.REQUEST_TIMED_OUT)
                check(!p.evaluate(snapshot(500)).requestTarget)
                p.evaluate(snapshot(1_000))
                check(p.phase == Phase.FAILED)
            },
            "late callback from an unknown attempt is ignored" to {
                val p = policy()
                val request = p.evaluate(snapshot(0))
                p.requestSucceeded(requireNotNull(request.requestAttempt) + 1, 10)
                p.requestFailed(request.requestAttempt + 1, RequestError.RUNTIME_EXCEPTION)
                check(p.phase == Phase.VERIFYING)
            },
            "endpoint disappearance fails safely" to {
                val p = policy()
                val request = p.evaluate(snapshot(0))
                p.requestFailed(requireNotNull(request.requestAttempt), RequestError.ENDPOINT_GONE)
                check(p.phase == Phase.FAILED)
                check(p.reasonCode == ReasonCode.REQUEST_ENDPOINT_GONE)
            },
            "another endpoint request cancellation stops automation" to {
                val p = policy()
                val request = p.evaluate(snapshot(0))
                p.requestFailed(requireNotNull(request.requestAttempt), RequestError.CANCELLED_BY_OTHER)
                check(p.phase == Phase.SUSPENDED)
                check(p.reasonCode == ReasonCode.REQUEST_CANCELLED_BY_OTHER)
            },
            "unspecified and runtime failures do not retry" to {
                for (error in listOf(RequestError.UNSPECIFIED, RequestError.RUNTIME_EXCEPTION)) {
                    val p = policy()
                    val request = p.evaluate(snapshot(0))
                    p.requestFailed(requireNotNull(request.requestAttempt), error)
                    check(p.phase == Phase.FAILED)
                    check(p.requests == 1)
                }
            },
            "authorization safety and call-count gates suspend" to {
                val invalid =
                    listOf(
                        snapshot(0, authorized = false) to ReasonCode.AUTHORIZATION_MISSING,
                        snapshot(0, safeCellularCall = false) to ReasonCode.UNSAFE_CALL,
                        snapshot(0, singleCall = false) to ReasonCode.MULTIPLE_CALLS,
                        snapshot(0, active = false) to ReasonCode.CALL_NOT_ACTIVE,
                        snapshot(0, enabled = false) to ReasonCode.DISABLED,
                    )
                for ((state, reason) in invalid) {
                    val p = policy()
                    check(!p.evaluate(state).requestTarget)
                    check(p.phase == Phase.SUSPENDED)
                    check(p.reasonCode == reason)
                }
            },
            "automatic mode requires projection" to {
                val disconnected = policy()
                disconnected.evaluate(snapshot(0, projection = false))
                check(disconnected.reasonCode == ReasonCode.PROJECTION_DISCONNECTED)
                val unknown = policy()
                check(unknown.evaluate(snapshot(0, projection = null)).wakeAt == 10_000L)
                check(unknown.reasonCode == ReasonCode.PROJECTION_UNKNOWN)
            },
            "manual mode bypasses only toggle and projection" to {
                val p = policy(manual = true)
                check(p.evaluate(snapshot(0, enabled = false, projection = false)).requestTarget)
                val unsafe = policy(manual = true)
                check(!unsafe.evaluate(snapshot(0, enabled = false, projection = false, authorized = false)).requestTarget)
            },
            "HFP connectivity and endpoint evidence are mandatory" to {
                val disconnected = policy()
                disconnected.evaluate(snapshot(0, targetHfpConnected = false))
                check(disconnected.reasonCode == ReasonCode.TARGET_HFP_DISCONNECTED)
                val unknown = policy()
                unknown.evaluate(snapshot(0, targetHfpConnected = null))
                check(unknown.reasonCode == ReasonCode.TARGET_HFP_UNKNOWN)
                val noSnapshot = policy()
                noSnapshot.evaluate(snapshot(0, targetAvailable = null))
                check(noSnapshot.reasonCode == ReasonCode.WAITING_ENDPOINT_SNAPSHOT)
                val missing = policy()
                missing.evaluate(snapshot(0, targetAvailable = false))
                check(missing.reasonCode == ReasonCode.WAITING_TARGET_ENDPOINT)
            },
            "unknown audio ownership waits instead of guessing" to {
                val p = policy()
                val decision = p.evaluate(snapshot(0, targetHfpAudio = null))
                check(!decision.requestTarget)
                check(p.reasonCode == ReasonCode.TARGET_HFP_UNKNOWN)
            },
            "evidence deadline fails without a route request" to {
                val p = policy()
                p.evaluate(snapshot(0, targetAvailable = false))
                p.evaluate(snapshot(10_000, targetAvailable = false))
                check(p.phase == Phase.FAILED)
                check(p.reasonCode == ReasonCode.EVIDENCE_DEADLINE_EXPIRED)
                check(p.requests == 0)
            },
            "external endpoint observations never drive the controller" to {
                val p = policy(settleDelayMs = 500)
                p.observeRoute(Route.OTHER_BLUETOOTH, 7)
                check(p.phase == Phase.WAITING)
                check(p.evaluate(snapshot(500)).requestTarget)
            },
            "explicit suspension and terminal phases are sticky" to {
                val p = policy()
                p.suspend("user")
                p.observeRoute(Route.TARGET, 1)
                p.requestSucceeded(1, 1)
                p.requestFailed(1, RequestError.TIMEOUT)
                check(!p.evaluate(snapshot(1, targetHfpAudio = true)).requestTarget)
                check(p.phase == Phase.SUSPENDED)
            },
            "already-active BMW audio releases without a request after stability" to {
                val p = policy(stableMs = 100)
                val first = p.evaluate(snapshot(0, targetHfpAudio = true, route = Route.TARGET))
                check(!first.requestTarget)
                check(p.phase == Phase.STABILIZING)
                p.evaluate(snapshot(100, targetHfpAudio = true, route = Route.TARGET))
                check(p.phase == Phase.RELEASED)
                check(p.requests == 0)
            },
        )
}
