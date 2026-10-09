import android.content.Context
import android.content.Intent
import android.content.MemoryPrefs
import android.os.TestQueue
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.ProjectionMonitor
import org.carcallrouter.companion.RouterLog
import org.carcallrouter.companion.RouterSettings
import org.carcallrouter.companion.SessionBridge
import org.carcallrouter.companion.telecom.AddressedTelecomRouter
import org.carcallrouter.companion.telecom.AudioFrameworkProbe
import org.carcallrouter.companion.telecom.CellularClassifier
import org.carcallrouter.companion.telecom.HfpMonitor
import org.carcallrouter.companion.telecom.RouterInCallService
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference

// Production service + deterministic framework boundaries. This is not Android emulation or a
// substitute for real-device Telecom/Bluetooth qualification.
private const val TARGET = "02:00:00:00:00:01"
private const val COMPETING = "02:00:00:00:00:02"
private const val OTHER = "02:00:00:00:00:03"
private val TARGET_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
private val COMPETING_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")
private val OTHER_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000003")
private val target = CallEndpoint("Target test device", CallEndpoint.TYPE_BLUETOOTH, TARGET_ID)
private val competing = CallEndpoint("Competing test device", CallEndpoint.TYPE_BLUETOOTH, COMPETING_ID)
private val other = CallEndpoint("Other test device", CallEndpoint.TYPE_BLUETOOTH, OTHER_ID)
private val speaker = CallEndpoint("Speaker", CallEndpoint.TYPE_SPEAKER)
private val handset = CallEndpoint("Handset", CallEndpoint.TYPE_EARPIECE)
private val wired = CallEndpoint("Wired", CallEndpoint.TYPE_WIRED_HEADSET)

private fun reset() {
    TestQueue.reset()
    Context.prefs = MemoryPrefs()
    Access.authorization = true
    Access.runtime = true
    Access.queryDelayMs = 0L
    RouterLog.events.clear()
    ProjectionMonitor.instances.clear()
    ProjectionMonitor.current = true
    CellularClassifier.pending = false
    CellularClassifier.instances.clear()
    HfpMonitor.instances.clear()
    HfpMonitor.labels =
        mapOf(
            TARGET to setOf(target.endpointName.toString()),
            COMPETING to setOf(competing.endpointName.toString()),
            OTHER to setOf(other.endpointName.toString()),
        )
    HfpMonitor.queryDelayMs = 0L
    HfpMonitor.isKnown = true
    HfpMonitor.devices = setOf(TARGET, COMPETING, OTHER)
    HfpMonitor.audioDevices = setOf(COMPETING)
    HfpMonitor.starts = 0
    HfpMonitor.refreshes = 0
    AudioFrameworkProbe.mode = "IN_CALL"
    AudioFrameworkProbe.device = "BLUETOOTH_SCO"
    SessionBridge.controller = null
}

private class Fixture(
    enabled: Boolean = true,
    projected: Boolean? = true,
    initialState: Int = Call.STATE_RINGING,
    initialEndpoint: CallEndpoint = competing,
    available: List<CallEndpoint> = listOf(target, competing, other),
) : AutoCloseable {
    val service: RouterInCallService
    val call: Call

    init {
        reset()
        ProjectionMonitor.current = projected
        RouterSettings(Context()).apply {
            setTarget(TARGET, "Target test device")
            setCompetitor(COMPETING, "Competing test device")
            this.enabled = enabled
        }
        service = RouterInCallService()
        service.currentCallEndpoint = initialEndpoint
        service.onCreate()
        service.onBind(Intent())
        service.onAvailableCallEndpointsChanged(available.toMutableList())
        service.onCallEndpointChanged(initialEndpoint)
        call = Call(Call.Details(initialState))
        service.onCallAdded(call)
        flush()
    }

    fun flush() = TestQueue.runReady()

    fun activateWithoutSettling() {
        call.deliverState(Call.STATE_ACTIVE)
        flush()
    }

    fun settle() {
        TestQueue.advanceTo(TestQueue.now + 500)
    }

    fun activateAndSettle() {
        activateWithoutSettling()
        settle()
    }

    fun route(endpoint: CallEndpoint) {
        service.currentCallEndpoint = endpoint
        service.onCallEndpointChanged(endpoint)
        flush()
    }

    fun targetAudio(active: Boolean) {
        HfpMonitor.audioDevices = if (active) setOf(TARGET) else setOf(COMPETING)
        HfpMonitor.emit(HfpMonitor.devices)
        flush()
    }

    fun endpoints(value: List<CallEndpoint>) {
        service.onAvailableCallEndpointsChanged(value.toMutableList())
        flush()
    }

    override fun close() {
        service.onDestroy()
        flush()
    }
}

private fun countEquals(
    fixture: Fixture,
    expected: Int,
) {
    check(fixture.service.issuedRequests.size == expected) {
        "Expected $expected requests, got ${fixture.service.issuedRequests}; ${SessionBridge.status}"
    }
}

fun main(args: Array<String>) {
    val tests =
        listOf<Pair<String, () -> Unit>>(
            "dialing_boundary_is_captured_with_early_routing" to {
                Fixture(initialState = Call.STATE_DIALING).use { f ->
                    TestQueue.advanceTo(500)
                    countEquals(f, 1)
                    check(RouterLog.events.any { it.first == "CALL_BOUNDARY_SNAPSHOT" && "state=DIALING" in it.second })
                    check(RouterLog.events.any { "bluetoothPriority=UNAVAILABLE_PUBLIC_API" in it.second })
                }
            },
            "short_active_race_preserves_call_timing_evidence" to {
                Fixture(initialState = Call.STATE_DIALING).use { f ->
                    TestQueue.advanceTo(700)
                    f.call.deliverState(Call.STATE_ACTIVE)
                    f.call.deliverState(Call.STATE_DISCONNECTED)
                    f.flush()
                    countEquals(f, 1)
                    check(
                        RouterLog.events.any {
                            it.first == "CALL_BOUNDARY_SNAPSHOT" && "state=ACTIVE" in it.second && "sinceAddedMs=700" in it.second
                        },
                    )
                    check(
                        RouterLog.events.any {
                            it.first == "CALL_BOUNDARY_SNAPSHOT" && "state=DISCONNECTED" in it.second && "sinceActiveMs=0" in it.second
                        },
                    )
                }
            },
            "initial_projection_false_is_rechecked_before_first_request" to {
                Fixture(projected = false).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 0)
                    ProjectionMonitor.emit(true)
                    f.flush()
                    TestQueue.advanceTo(TestQueue.now + 500)
                    countEquals(f, 1)
                }
            },
            "delayed_hfp_query_does_not_confirm_stale_audio" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    HfpMonitor.queryDelayMs = 2_000
                    HfpMonitor.audioDevices = setOf(TARGET)
                    HfpMonitor.emit(HfpMonitor.devices)
                    repeat(10) { TestQueue.advanceTo(TestQueue.now + 250) }
                    check(!SessionBridge.status.contains("reason=TARGET_AUDIO_CONFIRMED"))
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("HFP audio: UNKNOWN"))
                }
            },
            "late_post_confirmation_timer_marks_incomplete_observation" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(800)
                    HfpMonitor.audioDevices = emptySet()
                    AudioFrameworkProbe.mode = "NORMAL"
                    TestQueue.advanceTo(26_000)
                    check(RouterLog.events.any { "reason=observation_gap" in it.second })
                    f.call.deliverState(Call.STATE_DISCONNECTED)
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    val last = checkNotNull(RouterSettings(f.service).lastSession)
                    check(last.result == RouterSettings.Result.INCOMPLETE)
                    check(last.confirmation == "TARGET_HFP_AUDIO_OBSERVATION_INCOMPLETE")
                    countEquals(f, 1)
                }
            },
            "teardown_sco_loss_is_not_reported_as_instability" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(800)
                    HfpMonitor.audioDevices = emptySet()
                    HfpMonitor.emit(HfpMonitor.devices)
                    TestQueue.advanceTo(850)
                    f.call.deliverState(Call.STATE_DISCONNECTED)
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    check(RouterSettings(f.service).lastSession?.result != RouterSettings.Result.UNSTABLE)
                    countEquals(f, 1)
                }
            },
            "selector_recovery_missing_configuration_is_visible" to {
                Fixture(initialEndpoint = target).use { f ->
                    RouterSettings(f.service).setCompetitor(null, null)
                    f.activateAndSettle()
                    check(SessionBridge.status.contains("competing device not configured"))
                    countEquals(f, 1)
                }
            },
            "call_is_passive_until_active_and_settled" to {
                Fixture().use { f ->
                    countEquals(f, 0)
                    f.activateWithoutSettling()
                    countEquals(f, 0)
                    TestQueue.advanceTo(299)
                    countEquals(f, 0)
                    TestQueue.advanceTo(300)
                    countEquals(f, 1)
                }
            },
            "startup_endpoint_activity_gets_a_bounded_quiet_period" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateWithoutSettling()
                    TestQueue.advanceTo(100)
                    f.service.onCallEndpointRequested(other)
                    f.flush()
                    TestQueue.advanceTo(449)
                    countEquals(f, 0)
                    TestQueue.advanceTo(450)
                    countEquals(f, 1)
                }
            },
            "startup_audio_churn_delays_the_only_request_until_quiet" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateWithoutSettling()
                    TestQueue.advanceTo(100)
                    f.targetAudio(true)
                    TestQueue.advanceTo(200)
                    f.targetAudio(false)
                    TestQueue.advanceTo(549)
                    countEquals(f, 0)
                    TestQueue.advanceTo(550)
                    countEquals(f, 1)
                }
            },
            "silent_startup_audio_churn_is_sampled_before_the_only_request" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    val before = HfpMonitor.refreshes
                    HfpMonitor.audioDevices = setOf(OTHER)
                    TestQueue.advanceTo(250)
                    check(HfpMonitor.refreshes > before)
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTING_TRACE" &&
                                "event=HFP_SETTLING_SAMPLE" in it.second &&
                                "audio_owner=OTHER" in it.second
                        },
                    )
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTING_TRACE" &&
                                "event=STARTUP_AUDIO_ACTIVITY" in it.second &&
                                "source=settling_sample" in it.second
                        },
                    )
                    TestQueue.advanceTo(599)
                    countEquals(f, 0)
                    TestQueue.advanceTo(600)
                    countEquals(f, 1)
                }
            },
            "outgoing_connecting_active_uses_same_delay" to {
                Fixture(initialState = Call.STATE_DIALING).use { f ->
                    f.call.deliverState(Call.STATE_CONNECTING)
                    f.flush()
                    countEquals(f, 0)
                    f.activateAndSettle()
                    countEquals(f, 1)
                }
            },
            "split_brain_target_ui_with_competing_sco_forces_request" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("SCO=false"))
                    check(SessionBridge.status.contains("Telecom endpoint: TARGET"))
                    check(SessionBridge.status.contains("HFP audio: COMPETITOR; Selected device confirmed=false"))
                }
            },
            "outgoing_audio_change_without_broadcast_is_sampled_before_verdict" to {
                Fixture(initialState = Call.STATE_CONNECTING, initialEndpoint = target).use { f ->
                    HfpMonitor.audioDevices = setOf(TARGET)
                    HfpMonitor.emit(HfpMonitor.devices)
                    f.call.deliverState(Call.STATE_DIALING)
                    f.flush()
                    HfpMonitor.audioDevices = setOf(COMPETING)
                    HfpMonitor.emit(HfpMonitor.devices)
                    f.activateAndSettle()
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("HFP audio: COMPETITOR; Selected device confirmed=false"))

                    // The headset proxy changes after Telecom accepts, but the OEM does not
                    // deliver an audio-state broadcast. The verification timer must re-query it.
                    HfpMonitor.audioDevices = setOf(TARGET)
                    TestQueue.advanceTo(1_000)
                    check(SessionBridge.status.contains("STABILIZING"))
                    TestQueue.advanceTo(1_250)
                    check(SessionBridge.status.contains("TARGET_AUDIO_CONFIRMED"))
                    check(SessionBridge.status.contains("HFP audio: TARGET; Selected device confirmed=true"))
                    countEquals(f, 1)
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTING_TRACE" &&
                                "event=HFP_VERIFICATION_SAMPLE" in it.second &&
                                "audio_owner=TARGET" in it.second
                        },
                    )
                }
            },
            "failed_audio_verdict_uses_fresh_sample_and_never_retries" to {
                Fixture(initialEndpoint = target, available = listOf(target, other)).use { f ->
                    HfpMonitor.audioDevices = setOf(OTHER)
                    HfpMonitor.emit(HfpMonitor.devices)
                    f.activateAndSettle()
                    val before = HfpMonitor.refreshes
                    TestQueue.advanceTo(4_500)
                    check(HfpMonitor.refreshes > before)
                    check(SessionBridge.status.contains("TARGET_AUDIO_NOT_CONFIRMED"))
                    check(SessionBridge.status.contains("Telecom endpoint: TARGET"))
                    check(SessionBridge.status.contains("HFP audio: OTHER; Selected device confirmed=false"))
                    countEquals(f, 1)
                    val after = HfpMonitor.refreshes
                    TestQueue.advanceTo(10_000)
                    check(HfpMonitor.refreshes > after)
                    countEquals(f, 1)
                }
            },
            "audio_framework_mode_is_observed_without_controlling_telecom" to {
                Fixture(initialEndpoint = target).use { f ->
                    AudioFrameworkProbe.mode = "NORMAL"
                    AudioFrameworkProbe.device = "UNKNOWN"
                    f.activateAndSettle()
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("Audio framework: mode=NORMAL; device=UNKNOWN"))
                    AudioFrameworkProbe.mode = "IN_CALL"
                    AudioFrameworkProbe.device = "BLUETOOTH_SCO"
                    TestQueue.advanceTo(1_000)
                    check(SessionBridge.status.contains("mode=IN_CALL; device=BLUETOOTH_SCO"))
                    check(RouterLog.events.any { it.first == "ROUTING_TRACE" && "event=AUDIO_FRAMEWORK_STATE" in it.second })
                    countEquals(f, 1)
                }
            },
            "endpoint_snapshot_churn_after_request_does_not_hide_exact_audio_confirmation" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateAndSettle()
                    f.endpoints(listOf(competing, other))
                    f.targetAudio(true)
                    TestQueue.advanceTo(800)
                    check(SessionBridge.status.contains("TARGET_AUDIO_CONFIRMED"))
                    countEquals(f, 1)
                }
            },
            "late_audio_stream_before_deadline_confirms_without_second_request" to {
                Fixture(initialEndpoint = target).use { f ->
                    AudioFrameworkProbe.mode = "NORMAL"
                    AudioFrameworkProbe.device = "UNKNOWN"
                    f.activateAndSettle()
                    TestQueue.advanceTo(3_800)
                    countEquals(f, 1)
                    AudioFrameworkProbe.mode = "IN_CALL"
                    AudioFrameworkProbe.device = "BLUETOOTH_SCO"
                    f.targetAudio(true)
                    TestQueue.advanceTo(4_050)
                    check(SessionBridge.status.contains("TARGET_AUDIO_CONFIRMED"))
                    countEquals(f, 1)
                }
            },
            "verification_poll_has_bounded_query_budget" to {
                Fixture(initialEndpoint = target, available = listOf(target, other)).use { f ->
                    HfpMonitor.audioDevices = setOf(OTHER)
                    HfpMonitor.emit(HfpMonitor.devices)
                    f.activateAndSettle()
                    val baseline = HfpMonitor.refreshes
                    // Drive the fake uptime queue at the real 250 ms polling cadence. A
                    // single jump would execute only the first overdue Handler callback.
                    repeat(16) { TestQueue.advanceTo(TestQueue.now + 250) }
                    val periodicQueries = HfpMonitor.refreshes - baseline
                    check(periodicQueries in 10..18) { "Unexpected four-second HFP query budget: $periodicQueries" }
                    TestQueue.advanceTo(20_000)
                    check(HfpMonitor.refreshes - baseline in (periodicQueries + 1)..(periodicQueries + 2))
                    countEquals(f, 1)
                }
            },
            "external_request_after_target_suppresses_selector_recovery" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 1)
                    f.service.onCallEndpointRequested(other)
                    f.flush()
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("EXTERNAL_ENDPOINT_REQUEST"))
                    check(RouterLog.events.any { it.first == "ROUTING_TRACE" && "event=EXTERNAL_CONTROL_AFTER_TARGET" in it.second })
                }
            },
            "startup_endpoint_request_is_observational" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateWithoutSettling()
                    f.service.onCallEndpointRequested(competing)
                    f.flush()
                    check(!SessionBridge.status.contains("SUSPENDED"))
                    f.settle()
                    countEquals(f, 1)
                    check(
                        RouterLog.events.any {
                            it.first == "ENDPOINT_REQUEST_OBSERVED" && it.second.contains("observational=true")
                        },
                    )
                }
            },
            "dialer_requests_never_trigger_reassertion" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.service.onCallEndpointRequested(speaker)
                    f.route(speaker)
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 1)
                }
            },
            "target_audio_not_telecom_endpoint_completes_transaction" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.route(target)
                    check(SessionBridge.status.contains("VERIFYING"))
                    f.targetAudio(true)
                    check(SessionBridge.status.contains("STABILIZING"))
                    TestQueue.advanceTo(750)
                    check(SessionBridge.status.contains("RELEASED"))
                    check(SessionBridge.status.contains("TARGET_AUDIO_CONFIRMED"))
                }
            },
            "transient_target_audio_does_not_verify" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(600)
                    f.targetAudio(false)
                    TestQueue.advanceTo(750)
                    check(!SessionBridge.status.contains("RELEASED"))
                }
            },
            "failed_verification_never_sends_second_request" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("FAILED"))
                    check(SessionBridge.status.contains("TARGET_AUDIO_NOT_CONFIRMED"))
                }
            },
            "sleep_delays_settle_timer_without_unbounded_request" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateWithoutSettling()
                    TestQueue.sleepFor(13_000)
                    TestQueue.advanceTo(13_500)
                    countEquals(f, 0)
                    check(SessionBridge.status.contains("EVIDENCE_DEADLINE_EXPIRED"))
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTING_TRACE" &&
                                "event=TIMER_FIRED" in it.second &&
                                "late_ms=13250" in it.second &&
                                "sleep_delta_ms=13000" in it.second
                        },
                    )
                }
            },
            "sleep_delays_action_timer_without_reassertion" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 1)
                    TestQueue.sleepFor(16_000)
                    TestQueue.advanceTo(20_500)
                    check(f.service.issuedRequests.map { it.second } == listOf(TARGET_ID.toString(), COMPETING_ID.toString()))
                    check(SessionBridge.status.contains("SELECTOR_RECOVERY_ACCEPTED"))
                    TestQueue.advanceTo(23_000)
                    countEquals(f, 2)
                    check(SessionBridge.status.contains("SELECTOR_RECOVERY_NOT_CONFIRMED"))
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTING_TRACE" &&
                                "event=TIMER_FIRED" in it.second &&
                                "late_ms=19750" in it.second &&
                                "sleep_delta_ms=16000" in it.second
                        },
                    )
                }
            },
            "confirmed_audio_loss_and_return_are_observed_without_new_requests" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(750)
                    check(SessionBridge.status.contains("RELEASED"))
                    f.targetAudio(false)
                    f.targetAudio(true)
                    countEquals(f, 1)
                    val changes =
                        RouterLog.events
                            .filter { it.first == "ROUTING_TRACE" && "event=CONFIRMED_AUDIO_CHANGED" in it.second }
                            .map { it.second }
                    check(changes.size == 2)
                    check("target_sco=false" in changes[0] && "hfp_audio_owner=COMPETITOR" in changes[0])
                    check("target_sco=true" in changes[1] && "hfp_audio_owner=TARGET" in changes[1])
                }
            },
            "split_brain_failure_restores_phone_selector_once" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 1)
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 2)
                    check(f.service.issuedRequests[0].second == TARGET_ID.toString())
                    check(f.service.issuedRequests[1].second == COMPETING_ID.toString())
                    check(SessionBridge.status.contains("RECOVERING_SELECTOR"))
                    check(SessionBridge.status.contains("selectorRecoveries=1"))
                    f.route(competing)
                    check(SessionBridge.status.contains("SELECTOR_RECOVERY_CONFIRMED"))
                    TestQueue.advanceTo(10_000)
                    countEquals(f, 2)
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTING_TRACE" && it.second.contains("event=SELECTOR_RECOVERY_SUBMITTED")
                        },
                    )
                    val traces = RouterLog.events.filter { it.first == "ROUTING_TRACE" }.map { it.second }
                    check(traces.any { "event=SESSION_ENVIRONMENT" in it && "sdk=37" in it })
                    check(
                        traces.any {
                            "event=EVIDENCE_SNAPSHOT" in it &&
                                "route=TARGET" in it &&
                                "hfp_audio_owner=COMPETITOR" in it &&
                                "target_sco=false" in it
                        },
                    )
                    check(
                        traces.any {
                            "event=SELECTOR_RECOVERY_SUBMITTED" in it &&
                                "competitor_sco=true" in it &&
                                "endpoint_revision=" in it
                        },
                    )
                    check(
                        traces.any {
                            "event=SELECTOR_RECOVERY_CONTEXT" in it &&
                                "pre_route=TARGET" in it &&
                                "hfp_audio_owner=COMPETITOR" in it
                        },
                    )
                    check(traces.any { "event=SELECTOR_RECOVERY_CONFIRMED" in it })
                }
            },
            "selector_recovery_requires_exact_verified_split_brain" to {
                Fixture(initialEndpoint = target, available = listOf(target, other)).use { f ->
                    f.activateAndSettle()
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 1)
                }
                Fixture(initialEndpoint = target).use { f ->
                    f.activateAndSettle()
                    f.route(speaker)
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 1)
                }
            },
            "selector_recovery_rejection_never_retries" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.service.autoCompleteRequests = false
                    f.activateAndSettle()
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 2)
                    f.service.failLatest(CallEndpointException.ERROR_UNSPECIFIED)
                    f.flush()
                    check(SessionBridge.status.contains("SELECTOR_RECOVERY_FAILED"))
                    TestQueue.advanceTo(10_000)
                    countEquals(f, 2)
                }
            },
            "protected_user_routes_stop_selector_recovery" to {
                listOf(speaker, handset, wired, other).forEach { protectedRoute ->
                    Fixture(initialEndpoint = target).use { f ->
                        f.activateAndSettle()
                        TestQueue.advanceTo(4_500)
                        countEquals(f, 2)
                        f.route(protectedRoute)
                        check(SessionBridge.status.contains("SUSPENDED"))
                        check(SessionBridge.status.contains("USER_OVERRIDE"))
                        TestQueue.advanceTo(10_000)
                        countEquals(f, 2)
                        check(
                            RouterLog.events.any {
                                it.first == "ROUTING_TRACE" &&
                                    "event=POLICY_STATE" in it.second &&
                                    "reason=USER_OVERRIDE" in it.second
                            },
                        )
                    }
                }
            },
            "manual_one_shot_bypasses_toggle_and_projection" to {
                Fixture(enabled = false, projected = false).use { f ->
                    f.activateWithoutSettling()
                    f.service.routeNow()
                    f.flush()
                    countEquals(f, 1)
                    f.service.routeNow()
                    countEquals(f, 1)
                }
            },
            "manual_one_shot_retains_safety_gates" to {
                Fixture(enabled = false, projected = false).use { f ->
                    Access.authorization = false
                    f.activateWithoutSettling()
                    f.service.routeNow()
                    countEquals(f, 0)
                }
            },
            "disabled_auto_is_passive" to {
                Fixture(enabled = false).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
            },
            "missing_authorization_is_passive" to {
                Fixture().use { f ->
                    Access.authorization = false
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
            },
            "unsafe_call_is_passive" to {
                Fixture().use { f ->
                    f.call.rejection = "Emergency test boundary"
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
            },
            "projection_is_required_for_automatic_request" to {
                Fixture(projected = false).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
            },
            "target_hfp_connection_is_required" to {
                Fixture().use { f ->
                    HfpMonitor.emit(setOf(COMPETING, OTHER))
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
            },
            "target_endpoint_is_required_and_never_guessed" to {
                Fixture(available = listOf(competing, other)).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
                val duplicate = CallEndpoint("Target test device", CallEndpoint.TYPE_BLUETOOTH)
                Fixture(available = listOf(target, duplicate, competing)).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
            },
            "settings_change_stops_pending_transaction" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    RouterSettings(f.service).enabled = false
                    f.flush()
                    TestQueue.advanceTo(500)
                    countEquals(f, 0)
                    check(SessionBridge.status.contains("SETTINGS_CHANGED"))
                }
            },
            "hold_and_resume_does_not_restart_automation" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.call.deliverState(Call.STATE_HOLDING)
                    f.call.deliverState(Call.STATE_ACTIVE)
                    f.flush()
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 1)
                }
            },
            "second_call_cancels_the_transaction" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    val second = Call(Call.Details(Call.STATE_RINGING))
                    f.service.onCallAdded(second)
                    f.service.onCallRemoved(second)
                    f.flush()
                    TestQueue.advanceTo(500)
                    countEquals(f, 0)
                }
            },
            "projection_reconnect_before_request_can_proceed" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    ProjectionMonitor.emit(false)
                    ProjectionMonitor.emit(true)
                    f.flush()
                    TestQueue.advanceTo(500)
                    countEquals(f, 1)
                }
            },
            "manual_pause_prevents_further_action" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    f.service.pauseSession()
                    TestQueue.advanceTo(500)
                    countEquals(f, 0)
                }
            },
            "runtime_request_exception_fails_without_retry" to {
                Fixture().use { f ->
                    f.service.requestException = SecurityException("test")
                    f.activateAndSettle()
                    countEquals(f, 0)
                    check(SessionBridge.status.contains("FAILED"))
                    TestQueue.advanceTo(5_000)
                    countEquals(f, 0)
                }
            },
            "timeout_error_waits_without_retry" to {
                Fixture().use { f ->
                    f.service.autoCompleteRequests = false
                    f.activateAndSettle()
                    f.service.failLatest(CallEndpointException.ERROR_REQUEST_TIME_OUT)
                    f.flush()
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("FAILED"))
                }
            },
            "another_request_error_suspends" to {
                Fixture().use { f ->
                    f.service.autoCompleteRequests = false
                    f.activateAndSettle()
                    f.service.failLatest(CallEndpointException.ERROR_ANOTHER_REQUEST)
                    f.flush()
                    check(SessionBridge.status.contains("SUSPENDED"))
                    countEquals(f, 1)
                }
            },
            "endpoint_gone_fails_without_retry" to {
                Fixture().use { f ->
                    f.service.autoCompleteRequests = false
                    f.activateAndSettle()
                    f.service.failLatest(CallEndpointException.ERROR_ENDPOINT_DOES_NOT_EXIST)
                    f.flush()
                    check(SessionBridge.status.contains("FAILED"))
                    countEquals(f, 1)
                }
            },
            "late_result_from_finished_generation_is_ignored" to {
                Fixture().use { f ->
                    f.service.autoCompleteRequests = false
                    f.activateAndSettle()
                    f.call.deliverState(Call.STATE_DISCONNECTED)
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    f.service.completeLatest()
                    f.flush()
                    check(RouterLog.events.any { it.first == "ROUTE_RESULT_IGNORED" })
                }
            },
            "call_removal_cancels_scheduled_work" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    TestQueue.advanceTo(5_000)
                    countEquals(f, 0)
                    check(f.call.callbacks.isEmpty())
                }
            },
            "destroy_clears_callbacks_and_work" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    f.service.onDestroy()
                    check(f.call.callbacks.isEmpty())
                    check(TestQueue.size() == 0)
                }
            },
            "late_bind_active_call_uses_bounded_one_shot" to {
                Fixture(initialState = Call.STATE_ACTIVE).use { f ->
                    countEquals(f, 0)
                    f.settle()
                    countEquals(f, 1)
                }
            },
            "fresh_session_after_cleanup_can_route" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.service.onCallRemoved(f.call)
                    f.route(competing)
                    val next = Call(Call.Details(Call.STATE_RINGING))
                    f.service.onCallAdded(next)
                    f.flush()
                    next.deliverState(Call.STATE_ACTIVE)
                    f.flush()
                    TestQueue.advanceTo(TestQueue.now + 500)
                    countEquals(f, 2)
                }
            },
            "api37_callback_has_exact_platform_signature" to {
                val method = RouterInCallService::class.java.getDeclaredMethod("onCallEndpointRequested", CallEndpoint::class.java)
                check(method.returnType == Void.TYPE)
            },
            "target_audio_confirmation_is_traced_without_endpoint_agreement" to {
                Fixture(initialEndpoint = competing).use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(750)
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTING_TRACE" && it.second.contains("TARGET_HFP_AUDIO_CONFIRMED")
                        },
                    )
                }
            },
            "post_confirmation_watch_catches_silent_hfp_takeover_without_reassertion" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(800)
                    check(SessionBridge.status.contains("TARGET_AUDIO_CONFIRMED"))
                    val before = HfpMonitor.refreshes

                    // The headset proxy changes ownership without delivering an audio broadcast.
                    HfpMonitor.audioDevices = setOf(COMPETING)
                    TestQueue.advanceTo(1_050)
                    check(HfpMonitor.refreshes > before)
                    check(SessionBridge.status.contains("HFP audio: COMPETITOR; Selected device confirmed=false"))
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTING_TRACE" &&
                                "event=CONFIRMED_AUDIO_CHANGED" in it.second &&
                                "target_sco=false" in it.second
                        },
                    )
                    countEquals(f, 1)

                    repeat(14) { TestQueue.advanceTo(TestQueue.now + 250) }
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTING_TRACE" &&
                                "event=POST_CONFIRMATION_WATCH_FINISHED" in it.second &&
                                "loss_observed=true" in it.second
                        },
                    )
                    val after = HfpMonitor.refreshes
                    TestQueue.advanceTo(20_000)
                    check(HfpMonitor.refreshes > after)
                    countEquals(f, 1)
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    val last = checkNotNull(RouterSettings(f.service).lastSession)
                    check(last.confirmation == "TARGET_HFP_AUDIO_UNSTABLE")
                    check(last.result == RouterSettings.Result.UNSTABLE)
                }
            },
            "hfp_monitor_follows_projection_and_manual_lifecycle" to {
                Fixture(projected = false).use { f ->
                    check(HfpMonitor.starts == 0)
                    f.service.routeNow()
                    f.flush()
                    check(HfpMonitor.starts == 1)
                }
                Fixture(projected = true).use { f ->
                    check(HfpMonitor.starts == 1)
                    ProjectionMonitor.emit(false)
                    f.flush()
                    ProjectionMonitor.emit(true)
                    f.flush()
                    check(HfpMonitor.starts == 2)
                }
            },
            "confirmed_projection_loss_suspends_active_transaction" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    ProjectionMonitor.emit(false)
                    ProjectionMonitor.emit(true)
                    f.flush()
                    check(SessionBridge.status.contains("PROJECTION_DISCONNECTED"))
                    countEquals(f, 1)
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTING_TRACE" &&
                                "event=PROJECTION_CHANGED" in it.second &&
                                "active=false" in it.second
                        },
                    )
                }
            },
            "confirmed_hfp_loss_suspends_active_transaction" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    HfpMonitor.emit(setOf(COMPETING, OTHER))
                    HfpMonitor.emit(setOf(TARGET, COMPETING, OTHER))
                    f.flush()
                    check(SessionBridge.status.contains("TARGET_HFP_DISCONNECTED"))
                    countEquals(f, 1)
                }
            },
            "temporary_unknown_evidence_can_recover_before_request" to {
                Fixture(projected = null).use { f ->
                    f.activateWithoutSettling()
                    ProjectionMonitor.emit(true)
                    f.flush()
                    f.settle()
                    countEquals(f, 1)
                }
                Fixture().use { f ->
                    HfpMonitor.emit(emptySet(), known = false)
                    f.activateWithoutSettling()
                    f.settle()
                    countEquals(f, 0)
                    HfpMonitor.emit(setOf(TARGET, COMPETING, OTHER))
                    f.flush()
                    countEquals(f, 1)
                }
            },
            "details_and_conference_callbacks_are_safety_relevant" to {
                Fixture().use { f ->
                    f.call.deliverDetails()
                    val child = Call(Call.Details(Call.STATE_ACTIVE))
                    f.call.callbacks.toList().forEach {
                        it.onChildrenChanged(f.call, mutableListOf(child))
                        it.onChildrenChanged(f.call, mutableListOf())
                    }
                    f.flush()
                    check(SessionBridge.status.contains("CONFERENCE_OBSERVED"))
                    countEquals(f, 0)
                }
            },
            "active_without_pre_active_transition_is_suppressed" to {
                Fixture(initialState = Call.STATE_HOLDING).use { f ->
                    f.call.deliverState(Call.STATE_ACTIVE)
                    f.flush()
                    check(SessionBridge.status.contains("NO_FRESH_ACTIVE_TRANSITION"))
                    countEquals(f, 0)
                }
            },
            "same_active_call_rebind_is_suppressed" to {
                Fixture(initialState = Call.STATE_ACTIVE).use { f ->
                    f.settle()
                    countEquals(f, 1)
                    f.service.onUnbind(Intent())
                    f.service.onBind(Intent())
                    f.service.onAvailableCallEndpointsChanged(mutableListOf(target, competing, other))
                    f.service.onCallAdded(f.call)
                    f.flush()
                    check(SessionBridge.status.contains("ALREADY_ACTIVE_BIND"))
                    countEquals(f, 1)
                }
            },
            "late_bind_protected_route_and_timeout_are_suppressed" to {
                Fixture(projected = null, initialState = Call.STATE_ACTIVE, available = emptyList()).use { f ->
                    f.route(speaker)
                    check(SessionBridge.status.contains("ALREADY_ACTIVE_BIND"))
                    countEquals(f, 0)
                }
                Fixture(projected = null, initialState = Call.STATE_ACTIVE).use { f ->
                    check(SessionBridge.status.contains("LATE_BIND_RECOVERY_EVIDENCE"))
                    TestQueue.advanceTo(5_000)
                    check(SessionBridge.status.contains("ALREADY_ACTIVE_BIND"))
                    countEquals(f, 0)
                }
            },
            "late_bind_waits_for_endpoint_and_handles_ineligible_state" to {
                Fixture(initialState = Call.STATE_ACTIVE, available = emptyList()).use { f ->
                    check(SessionBridge.status.contains("LATE_BIND_RECOVERY_EVIDENCE"))
                    f.endpoints(listOf(target, competing, other))
                    f.settle()
                    countEquals(f, 1)
                }
                Fixture(enabled = false, initialState = Call.STATE_ACTIVE).use { f ->
                    f.settle()
                    countEquals(f, 0)
                    check(SessionBridge.status.contains("DISABLED"))
                }
            },
            "all_platform_route_types_are_observed" to {
                val streaming = CallEndpoint("Streaming", CallEndpoint.TYPE_STREAMING)
                val unknown = CallEndpoint("Unknown", CallEndpoint.TYPE_UNKNOWN)
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    listOf(handset, wired, streaming, other, unknown).forEach(f::route)
                    HfpMonitor.emit(emptySet(), known = false)
                    f.route(other)
                    val traces = RouterLog.events.filter { it.first == "ROUTING_TRACE" }.map { it.second }
                    listOf("HANDSET", "WIRED", "STREAMING", "OTHER_BLUETOOTH", "UNKNOWN").forEach { route ->
                        check(traces.any { "route=$route" in it })
                    }
                }
            },
            "structured_evidence_classifies_every_hfp_audio_owner" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    HfpMonitor.audioDevices = emptySet()
                    HfpMonitor.emit(HfpMonitor.devices, known = false)
                    f.flush()
                    HfpMonitor.emit(setOf(TARGET, COMPETING, OTHER))
                    f.flush()
                    HfpMonitor.audioDevices = setOf(OTHER)
                    HfpMonitor.emit(HfpMonitor.devices)
                    f.flush()
                    HfpMonitor.audioDevices = setOf(TARGET, COMPETING)
                    HfpMonitor.emit(HfpMonitor.devices)
                    f.flush()
                    HfpMonitor.audioDevices = setOf(TARGET)
                    HfpMonitor.emit(HfpMonitor.devices)
                    f.flush()
                    val traces = RouterLog.events.filter { it.first == "ROUTING_TRACE" }.map { it.second }
                    listOf("UNKNOWN", "NONE", "OTHER", "MULTIPLE", "TARGET").forEach { owner ->
                        check(traces.any { "event=EVIDENCE_SNAPSHOT" in it && "hfp_audio_owner=$owner" in it })
                    }
                }
            },
            "request_error_fallback_and_late_rejection_are_covered" to {
                Fixture().use { f ->
                    f.service.autoCompleteRequests = false
                    f.activateAndSettle()
                    f.service.failLatest(CallEndpointException.ERROR_UNSPECIFIED)
                    f.flush()
                    check(SessionBridge.status.contains("REQUEST_FAILED"))
                }
                Fixture().use { f ->
                    f.service.autoCompleteRequests = false
                    f.activateAndSettle()
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    f.service.failLatest(CallEndpointException.ERROR_UNSPECIFIED)
                    f.flush()
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTE_RESULT_IGNORED" && it.second.contains("result=rejected")
                        },
                    )
                }
            },
            "preferred_device_connecting_after_answer_routes_within_startup_window" to {
                Fixture().use { f ->
                    HfpMonitor.emit(setOf(COMPETING, OTHER))
                    f.flush()
                    f.activateAndSettle()
                    countEquals(f, 0)
                    check(SessionBridge.status.contains("WAITING_TARGET_HFP"))
                    TestQueue.advanceTo(1000)
                    HfpMonitor.emit(setOf(TARGET, COMPETING, OTHER))
                    f.flush()
                    countEquals(f, 1)
                }
            },
            "preferred_device_connecting_after_startup_deadline_does_not_take_over" to {
                Fixture().use { f ->
                    HfpMonitor.emit(setOf(COMPETING, OTHER))
                    f.flush()
                    f.activateAndSettle()
                    TestQueue.advanceTo(11000)
                    HfpMonitor.emit(setOf(TARGET, COMPETING, OTHER))
                    f.flush()
                    countEquals(f, 0)
                    check(SessionBridge.status.contains("EVIDENCE_DEADLINE_EXPIRED"))
                }
            },
            "renamed_headset_routes_using_live_bluetooth_identity" to {
                Fixture().use { f ->
                    val renamed = CallEndpoint("Renamed headset", CallEndpoint.TYPE_BLUETOOTH, TARGET_ID)
                    HfpMonitor.labels =
                        mapOf(
                            TARGET to setOf("Renamed headset", "Headset factory name"),
                            COMPETING to setOf("Competing test device"),
                            OTHER to setOf("Other test device"),
                        )
                    f.endpoints(listOf(renamed, competing, other))
                    f.activateAndSettle()
                    countEquals(f, 1)
                    check(
                        f.service.issuedRequests
                            .single()
                            .second == TARGET_ID.toString(),
                    )
                    f.targetAudio(true)
                    TestQueue.advanceTo(1000)
                    check(SessionBridge.status.contains("reason=TARGET_AUDIO_CONFIRMED"))
                    check(RouterLog.events.none { "Renamed headset" in it.second || "Headset factory name" in it.second })
                }
            },
            "duplicate_live_bluetooth_names_suppress_automatic_request" to {
                Fixture().use { f ->
                    HfpMonitor.labels =
                        mapOf(
                            TARGET to setOf("Target test device"),
                            OTHER to setOf("Target test device"),
                            COMPETING to setOf("Competing test device"),
                        )
                    f.activateAndSettle()
                    countEquals(f, 0)
                    TestQueue.advanceTo(11000)
                    countEquals(f, 0)
                    check(SessionBridge.status.contains("Connected Bluetooth devices share"))
                }
            },
            "pending_live_names_never_route_using_saved_target_label" to {
                Fixture().use { f ->
                    HfpMonitor.labels = emptyMap()
                    f.activateAndSettle()
                    countEquals(f, 0)
                    HfpMonitor.labels =
                        mapOf(
                            TARGET to setOf(target.endpointName.toString()),
                            COMPETING to setOf(competing.endpointName.toString()),
                            OTHER to setOf(other.endpointName.toString()),
                        )
                    HfpMonitor.emit(setOf(TARGET, COMPETING, OTHER))
                    f.flush()
                    TestQueue.advanceTo(TestQueue.now + 500)
                    countEquals(f, 1)
                }
            },
            "missing_competitor_name_cannot_prove_target_identity" to {
                Fixture().use { f ->
                    HfpMonitor.labels = mapOf(TARGET to setOf(target.endpointName.toString()))
                    f.activateAndSettle()
                    TestQueue.advanceTo(11000)
                    countEquals(f, 0)
                }
            },
            "name_evidence_loss_after_request_does_not_fight_or_map_another_peer" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    countEquals(f, 1)
                    HfpMonitor.labels = emptyMap()
                    f.targetAudio(true)
                    TestQueue.advanceTo(1000)
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("reason=TARGET_AUDIO_CONFIRMED"))
                }
            },
            "adapter_rejects_old_callback_instance_with_reused_uuid" to {
                reset()
                val service = InCallService()
                val adapter = AddressedTelecomRouter(service)
                adapter.updateAvailable(listOf(target))
                val replacement = CallEndpoint("Renamed test headset", CallEndpoint.TYPE_BLUETOOTH, TARGET_ID)
                adapter.updateAvailable(listOf(replacement))
                check(runCatching { adapter.request(target, {}, {}, { _, _ -> }) }.isFailure)
                check(service.issuedRequests.isEmpty())
                adapter.request(replacement, {}, {}, { _, _ -> error("unexpected rejection") })
                check(service.issuedRequests.single().second == replacement.identifier.toString())
            },
            "adapter_rejects_stale_endpoint_and_clears_runtime_marker" to {
                reset()
                val service = InCallService()
                val adapter = AddressedTelecomRouter(service)
                adapter.updateAvailable(listOf(competing))
                check(runCatching { adapter.request(target, {}, {}, { _, _ -> }) }.isFailure)
                service.requestException = SecurityException("test")
                adapter.updateAvailable(listOf(target))
                check(runCatching { adapter.request(target, {}, {}, { _, _ -> }) }.isFailure)
                check(adapter.observeRequest(target).origin == AddressedTelecomRouter.RequestOrigin.EXTERNAL)
            },
            "settings_and_session_bridge_diagnostics_are_round_tripped" to {
                reset()
                val settings = RouterSettings(Context())
                check(settings.lastSession == null)
                check(settings.lastBound == 0L)
                settings.setTarget(TARGET, "Target test device")
                settings.setCompetitor(COMPETING, "Competing test device")
                check(runCatching { settings.setCompetitor(TARGET.lowercase(), "invalid") }.isFailure)
                settings.setTarget(COMPETING.lowercase(), "Replacement target")
                check(settings.competitorAddress == null)
                settings.markBound()
                check(settings.lastBound > 0L)
                settings.recordLastSession("RELEASED", "TARGET_AUDIO_CONFIRMED", "TARGET_HFP_AUDIO")
                val last = checkNotNull(settings.lastSession)
                check(last.phase == "RELEASED")
                check(last.reason == "TARGET_AUDIO_CONFIRMED")
                check(last.confirmation == "TARGET_HFP_AUDIO")

                var updates = 0
                val listener = {
                    updates++
                    Unit
                }
                SessionBridge.observe(listener)
                SessionBridge.publish("test")
                SessionBridge.remove(listener)
                SessionBridge.publish("ignored")
                check(updates == 2)
                SessionBridge.controller = WeakReference(null)
            },
            "unbind_dump_and_call_state_names_are_covered" to {
                Fixture(initialState = Call.STATE_NEW).use { f ->
                    f.call.deliverState(Call.STATE_SELECT_PHONE_ACCOUNT)
                    f.call.deliverState(Call.STATE_DIALING)
                    f.call.deliverState(Call.STATE_CONNECTING)
                    f.call.deliverState(Call.STATE_ACTIVE)
                    f.flush()
                    f.call.deliverState(Call.STATE_DISCONNECTING)
                    f.call.deliverState(99)
                    f.flush()
                    val output = StringWriter()
                    f.service.dump(FileDescriptor.out, PrintWriter(output, true), emptyArray())
                    check("Android Auto Call Switcher (redacted)" in output.toString())
                    check(!f.service.onUnbind(Intent()))
                    check(SessionBridge.controller == null)
                }
            },
            "completed_call_persists_verified_session" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(750)
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    val last = checkNotNull(RouterSettings(f.service).lastSession)
                    check(last.phase == "RELEASED")
                    check(last.reason == "TARGET_AUDIO_CONFIRMED")
                    check(last.confirmation == "TARGET_HFP_AUDIO")
                    check(last.result == RouterSettings.Result.HFP_CONFIRMED)
                }
            },
            "unknown_audio_during_watch_is_incomplete" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(750)
                    HfpMonitor.isKnown = false
                    HfpMonitor.instances.last().refresh()
                    f.flush()
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    check(RouterSettings(f.service).lastSession?.result == RouterSettings.Result.INCOMPLETE)
                    countEquals(f, 1)
                }
            },
            "normal_disconnect_retains_confirmed_last_call_result" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(750)
                    f.call.deliverState(Call.STATE_DISCONNECTED)
                    f.flush()
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    val last = checkNotNull(RouterSettings(f.service).lastSession)
                    check(last.phase == "SUSPENDED")
                    check(last.reason == "CALL_NOT_ACTIVE")
                    check(last.result == RouterSettings.Result.HFP_CONFIRMED)
                }
            },
            "request_markers_expire_and_are_generation_bound" to {
                reset()
                var now = 0L
                val service = InCallService()
                val adapter = AddressedTelecomRouter(service) { now }
                adapter.updateAvailable(listOf(target))
                adapter.request(target, {}, {}, { _, _ -> error("unexpected") })
                now = AddressedTelecomRouter.REQUEST_MARKER_TTL_MS + 1
                val expired = adapter.observeRequest(target)
                check(expired.origin == AddressedTelecomRouter.RequestOrigin.EXTERNAL)
                check(expired.expiredCount == 1)
                adapter.request(target, {}, {}, { _, _ -> error("unexpected") })
                val generation = adapter.generation()
                adapter.clearSession()
                check(adapter.generation() == generation + 1)
                check(adapter.observeRequest(target).origin == AddressedTelecomRouter.RequestOrigin.EXTERNAL)
            },
            "removed_endpoint_invalidates_cached_current" to {
                reset()
                val service = InCallService().also { it.currentCallEndpoint = target }
                val adapter = AddressedTelecomRouter(service)
                adapter.updateAvailable(listOf(target, competing))
                adapter.updateCurrent(target)
                check(adapter.current()?.identifier == TARGET_ID)
                adapter.updateAvailable(listOf(competing))
                check(adapter.current() == null)
            },
            "wrong_audio_report_does_not_route_or_start_an_orphan_session" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(750)
                    val requests = f.service.issuedRequests.size
                    f.service.reportWrongAudio()
                    f.flush()
                    check(f.service.issuedRequests.size == requests)
                    check(RouterLog.events.any { it.first == "ROUTING_TRACE" && "event=USER_AUDIO_REPORT" in it.second })
                    f.call.deliverState(Call.STATE_DISCONNECTED)
                    f.flush()
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    val starts = RouterLog.events.count { it.first == "ROUTING_TRACE" && "event=SESSION_STARTED" in it.second }
                    f.service.reportWrongAudio()
                    f.flush()
                    check(RouterLog.events.count { it.first == "ROUTING_TRACE" && "event=SESSION_STARTED" in it.second } == starts)
                }
            },
            "authorization_probe_stall_does_not_block_evaluation_or_authorize_routing" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    Access.queryDelayMs = 24_400
                    f.endpoints(listOf(target, competing, other))
                    check("AUTHORIZATION_UNKNOWN" in SessionBridge.status)
                    check(RouterLog.events.none { "duration_ms=24400" in it.second })
                    TestQueue.advanceTo(10_000)
                    countEquals(f, 0)
                    check("EVIDENCE_DEADLINE_EXPIRED" in SessionBridge.status)
                }
            },
            "early_routing_then_answer_keeps_one_request_if_audio_stays_bmw" to {
                Fixture(initialState = Call.STATE_DIALING).use { f ->
                    TestQueue.advanceTo(500)
                    countEquals(f, 1)
                    f.targetAudio(true)
                    TestQueue.advanceTo(800)
                    f.activateWithoutSettling()
                    TestQueue.advanceTo(1200)
                    TestQueue.advanceTo(1500)
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("Selected device confirmed=true"))
                }
            },
            "answer_during_pending_dialing_request_does_not_replace_it" to {
                Fixture(initialState = Call.STATE_DIALING).use { f ->
                    TestQueue.advanceTo(500)
                    f.activateWithoutSettling()
                    TestQueue.advanceTo(900)
                    countEquals(f, 1)
                    f.targetAudio(true)
                    TestQueue.advanceTo(1200)
                    check(SessionBridge.status.contains("Selected device confirmed=true"))
                }
            },
            "long_dialing_timeout_then_answer_has_a_separate_bounded_pass" to {
                Fixture(initialState = Call.STATE_DIALING).use { f ->
                    TestQueue.advanceTo(500)
                    TestQueue.advanceTo(5000)
                    countEquals(f, 1)
                    f.activateWithoutSettling()
                    TestQueue.advanceTo(5500)
                    countEquals(f, 2)
                    f.targetAudio(true)
                    TestQueue.advanceTo(5800)
                    repeat(20) { TestQueue.advanceTo(TestQueue.now + 250) }
                    countEquals(f, 2)
                }
            },
            "answer_time_takeover_after_dialing_confirmation_has_one_recovery" to {
                Fixture(initialState = Call.STATE_DIALING).use { f ->
                    TestQueue.advanceTo(500)
                    f.targetAudio(true)
                    TestQueue.advanceTo(800)
                    // No explicit external request: simulate platform answer-time ownership.
                    HfpMonitor.audioDevices = setOf(COMPETING)
                    f.activateWithoutSettling()
                    TestQueue.advanceTo(1300)
                    countEquals(f, 2)
                    repeat(25) { TestQueue.advanceTo(TestQueue.now + 250) }
                    countEquals(f, 2)
                }
            },
            "explicit_external_choice_during_dialing_prevents_answer_reassertion" to {
                Fixture(initialState = Call.STATE_DIALING).use { f ->
                    TestQueue.advanceTo(500)
                    f.service.onCallEndpointRequested(competing)
                    f.flush()
                    f.activateWithoutSettling()
                    TestQueue.advanceTo(2000)
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("reason=USER_OVERRIDE"))
                }
            },
            "answered_call_manual_route_before_settling_is_preserved" to {
                listOf(speaker, handset, wired).forEach { selected ->
                    Fixture().use { f ->
                        f.activateWithoutSettling()
                        f.route(selected)
                        // A later platform callback must not erase the protected-route event.
                        f.route(competing)
                        TestQueue.advanceTo(2000)
                        countEquals(f, 0)
                        check(SessionBridge.status.contains("reason=USER_OVERRIDE"))
                    }
                }
            },
            "manual_route_during_answer_verification_prevents_recovery" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 1)
                    f.route(speaker)
                    f.route(target)
                    TestQueue.advanceTo(5000)
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("reason=USER_OVERRIDE"))
                }
            },
            "speaker_choice_during_dialing_is_preserved_at_answer" to {
                Fixture(initialState = Call.STATE_DIALING).use { f ->
                    TestQueue.advanceTo(500)
                    f.route(speaker)
                    f.activateWithoutSettling()
                    TestQueue.advanceTo(2000)
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("reason=USER_OVERRIDE"))
                }
            },
            "unanswered_dialing_stops_without_answer_recovery" to {
                Fixture(initialState = Call.STATE_DIALING).use { f ->
                    TestQueue.advanceTo(500)
                    f.call.deliverState(Call.STATE_DISCONNECTED)
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    TestQueue.advanceTo(2000)
                    countEquals(f, 1)
                    check(RouterLog.events.any { "event=CALL_DURATION_SUMMARY" in it.second && "connected_duration_ms=null" in it.second })
                }
            },
            "late_silent_audio_loss_is_observed_without_fighting_user" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    repeat(40) { TestQueue.advanceTo(TestQueue.now + 250) }
                    HfpMonitor.audioDevices = setOf(COMPETING)
                    repeat(24) { TestQueue.advanceTo(TestQueue.now + 250) }
                    countEquals(f, 1)
                    check(
                        RouterLog.events.any {
                            "event=CALL_AUDIO_OBSERVATION" in it.second &&
                                "target_sco=false" in it.second &&
                                "since_answer_ms=" in it.second
                        },
                    )
                    f.call.deliverState(Call.STATE_DISCONNECTED)
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    check(RouterSettings(f.service).lastSession?.confirmation == "TARGET_HFP_AUDIO_UNSTABLE")
                }
            },
            "full_call_observation_is_bounded_and_stops_on_aa_disconnect" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    repeat(240) { TestQueue.advanceTo(TestQueue.now + 250) }
                    check(HfpMonitor.refreshes in 30..80)
                    ProjectionMonitor.emit(false)
                    f.flush()
                    val before = HfpMonitor.refreshes
                    repeat(30) { TestQueue.advanceTo(TestQueue.now + 250) }
                    check(HfpMonitor.refreshes == before)
                    countEquals(f, 1)
                }
            },
            "wrong_audio_report_is_not_cleared_by_route_now" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(800)
                    f.service.reportWrongAudio()
                    f.service.routeNow()
                    repeat(4) { TestQueue.advanceTo(TestQueue.now + 250) }
                    f.call.deliverState(Call.STATE_DISCONNECTED)
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    check(RouterLog.events.any { "event=SESSION_FINISHED" in it.second && "user_reported_wrong_audio=true" in it.second })
                    check(RouterSettings(f.service).lastSession?.confirmation == "TARGET_HFP_AUDIO_UNSTABLE")
                }
            },
            "request_events_include_answer_relative_and_dialing_relative_timing" to {
                Fixture(initialState = Call.STATE_DIALING).use { f ->
                    TestQueue.advanceTo(500)
                    check(
                        RouterLog.events.any {
                            "event=REQUEST_SUBMITTED" in it.second &&
                                "stage=DIALING" in it.second &&
                                "since_dialing_ms=500" in it.second &&
                                "since_answer_ms=null" in it.second
                        },
                    )
                    f.activateWithoutSettling()
                    f.service.onCallEndpointRequested(target)
                    f.flush()
                    check(RouterLog.events.any { "event=ENDPOINT_REQUEST_OBSERVED" in it.second && "since_answer_ms=0" in it.second })
                }
            },
            "pending_call_safety_waits_then_routes_after_completion" to {
                Fixture().use { f ->
                    CellularClassifier.pending = true
                    f.activateAndSettle()
                    countEquals(f, 0)
                    check("WAITING_CALL_SAFETY" in SessionBridge.status)
                    CellularClassifier.complete()
                    f.flush()
                    countEquals(f, 1)
                }
            },
            "pending_call_safety_times_out_without_endpoint_request" to {
                Fixture().use { f ->
                    CellularClassifier.pending = true
                    f.activateAndSettle()
                    TestQueue.advanceTo(10_000)
                    countEquals(f, 0)
                    check("EVIDENCE_DEADLINE_EXPIRED" in SessionBridge.status)
                }
            },
            "late_bind_pending_safety_retains_its_evidence_window" to {
                Fixture(initialState = Call.STATE_ACTIVE, projected = null).use { f ->
                    CellularClassifier.pending = true
                    ProjectionMonitor.emit(true)
                    f.flush()
                    countEquals(f, 0)
                    CellularClassifier.complete()
                    f.flush()
                    f.settle()
                    countEquals(f, 1)
                }
            },
            "late_service_shutdown_marks_observation_incomplete" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(800)
                    f.service.onDestroy()
                    check(
                        RouterLog.events.any {
                            "event=SESSION_FINISHED" in it.second &&
                                "termination=service_stopped" in it.second &&
                                "observation_incomplete=true" in it.second
                        },
                    )
                }
            },
            "accepted_target_endpoint_without_sco_remains_failure_for_full_call" to {
                Fixture(initialEndpoint = target).use { f ->
                    RouterSettings(f.service).setCompetitor(null, null)
                    f.flush()
                    f.activateAndSettle()
                    f.flush()
                    repeat(240) { TestQueue.advanceTo(TestQueue.now + 250) }
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("Selected device confirmed=false"))
                    check(SessionBridge.status.contains("TARGET_AUDIO_NOT_CONFIRMED"))
                    check(RouterLog.events.any { "event=CALL_AUDIO_OBSERVATION" in it.second && "target_sco=false" in it.second })
                    f.call.deliverState(Call.STATE_DISCONNECTED)
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    check(RouterLog.events.none { "event=TARGET_HFP_AUDIO_CONFIRMED" in it.second })
                }
            },
            "endpoint_request_correlation_never_claims_caller_identity" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.service.onCallEndpointRequested(target)
                    f.flush()
                    check(RouterLog.events.any { "event=ENDPOINT_REQUEST_OBSERVED" in it.second && "request_source=UNKNOWN" in it.second })
                }
            },
            "projection_provider_evidence_and_single_failure_incident_are_exported" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    TestQueue.advanceTo(4_500)
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTING_TRACE" &&
                                "event=EVIDENCE_SNAPSHOT" in it.second &&
                                "projection_status=CONNECTED" in it.second &&
                                "projection_raw_state=2" in it.second
                        },
                    )
                    val incidents = RouterLog.events.count { it.first == "ROUTING_TRACE" && "event=DIAGNOSTIC_INCIDENT" in it.second }
                    check(incidents == 1)
                    f.endpoints(listOf(target, competing, other))
                    check(RouterLog.events.count { it.first == "ROUTING_TRACE" && "event=DIAGNOSTIC_INCIDENT" in it.second } == incidents)
                }
            },
        )

    val failed = mutableListOf<String>()
    val output = mutableListOf<String>()
    for ((name, test) in tests) {
        try {
            test()
            output.add("PASS $name")
        } catch (error: Throwable) {
            failed.add(name)
            output.add("FAIL $name: ${error.message}")
        }
    }
    output.add("RESULT ${tests.size - failed.size}/${tests.size} passed; failures=${failed.size}")
    output.add(
        "SCOPE: production Kotlin service/adapter/policy on deterministic JVM doubles; " +
            "NOT Android installation, platform admission, Bluetooth or audio validation.",
    )
    output.forEach(::println)
    args.firstOrNull()?.let { File(it).writeText(output.joinToString("\n") + "\n") }
    check(failed.isEmpty()) { "Service scenarios failed: ${failed.joinToString()}" }
}
