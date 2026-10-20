# Routing review: platform contracts, design and verification

## Findings from the failed calls

Recent private device traces show Telecom accepting the already-displayed preferred endpoint in milliseconds while the sampled HFP owner remains another device. The owner's physical observations also contradict the displayed route. Request acceptance, the selector, sampled HFP ownership and audible speaker/microphone behavior are separate facts. None may silently substitute for another.

An optional competitor setting was unset in the affected traces. Recovery depending on that setting could not act. Older code also interpreted call-start request notifications as possible manual overrides and ended selector repair without completing the preferred-device transition. These are reproducible software defects, although the traces cannot expose every internal vendor Bluetooth decision.

Private device aliases, addresses, call identifiers, timestamps and recordings are deliberately absent from this repository. Regression fixtures use synthetic identities and timing.

## Primary-source review, 2026-10-20

| Contract or implementation | Finding and consequence |
|---|---|
| [AOSP CallEndpointController](https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/main/src/com/android/server/telecom/CallEndpointController.java) | `requestCallEndpointChange` returns success without `setAudioRoute` when Telecom already considers the endpoint selected. Repeating this command cannot be assumed to initiate an audio transition. |
| [AOSP CallAudioRouteController, pinned revision](https://android.googlesource.com/platform/packages/services/Telecomm/+/9b9f5af7ee0a7dba65ba2dafd24e918a9aa22caa/src/com/android/server/telecom/CallAudioRouteController.java) | Lower routing also has a same-route/address early return. Substituting the deprecated address-based request is not a reliable escape. |
| [InCallService](https://developer.android.com/reference/android/telecom/InCallService) | Requests must use current callback-supplied endpoints. Endpoint-request notifications are not completed changes and do not establish a human gesture. Default-dialer status requires a complete calling UI; the documented companion access path concerns wearable devices. |
| [CallEndpoint](https://developer.android.com/reference/android/telecom/CallEndpoint) | Public endpoints expose name, type and identifier, without a Bluetooth hardware address. SDK 37.0 was also inspected directly. Name/alias correlation is an OEM-dependent inference, even when unique; no universal address-mapping guarantee exists. |
| [BluetoothHeadset implementation](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/0b8a3af3cc/framework/java/android/bluetooth/BluetoothHeadset.java) | Public `isAudioConnected` can return false after service/IPC failure. A negative target result alone cannot establish another owner; reconciliation requires a positive, single alternative owner. |
| [AudioManager](https://developer.android.com/reference/android/media/AudioManager) and [Telecom overview](https://developer.android.com/develop/connectivity/telecom) | Communication-device requests prioritize the audio-mode owner. Owning an app's VoIP call is different from controlling an existing cellular call. Another audio-control API would not grant this companion exclusive cellular routing authority. |
| [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations) | Separate data ownership and decisions, expose state to the UI, and apply recommendations to the app's actual needs. The routing reducer, platform adapters and observable audio assessment are the important boundaries here. |

The AOSP no-op is a concrete source-backed mechanism consistent with the traces, not proof of identical Samsung internals. The unchanged protected AppOps setup remains an advanced, device-qualified admission path, not a normal runtime permission or a portable consumer-app entitlement. Neither replacing Samsung Phone nor adding accessibility/hidden-API automation removes the evidenced audio-state problem.

## Decision: reconcile before one target request

The previous proposed repair waited for a failed target request, aligned the selector and retried. That was unnecessary complexity: the initial mismatch already predicts an idempotent request. Beta.21 instead plans a single target transition:

1. Collect evidence before answer without changing routes. After ACTIVE, allow the bounded 300–900 ms startup quiet window.
2. If fresh target HFP observations already persist for 250 ms, finish without a command. Otherwise, when Telecom displays another endpoint, make the single target request if all eligibility and identity checks pass.
3. If Telecom already displays the target while audio belongs elsewhere, do not submit an idempotent target request. Require a uniquely resolved, sole positive alternative HFP owner, sampled consistently for at least 500 ms with gaps no greater than 750 ms. A configured competitor restricts which owner is permitted; without it, fresh live identity must resolve the sole observed owner.
4. Reconcile Telecom to that observed owner once. Require its exact current endpoint and a matching HFP query started strictly after the command, within 2.5 seconds. A successful outcome alone is insufficient. The 2.5-second budget covers AOSP's two-second endpoint timeout plus two 250 ms sampling/dispatch intervals; the separate 1.5-second target outcome watchdog only switches to continued audio observation.
5. Make the single target request, then allow four seconds for fresh target HFP samples spanning 250 ms. Never retry after this request. Continue passive observation and truthful status reporting throughout the call.

The maximum is **one reconciliation plus one target command**, versus one command for an ordinary transition. Unresolved mismatch waits within the ten-second evidence window and fails without guessing. Owner changes, external control, loss of eligibility, ambiguous devices and expired evidence cannot authorize the next command. Protected user routes stop automation. Android requests already submitted cannot be recalled.

Manual Route now explicitly begins the same bounded transaction, bypassing only the automation toggle and projection requirement. An unfinished reconciliation blocks overlapping manual requests even before a target command exists. Manual reconciliation continues sampling without projection or broadcasts.

## Implementation boundaries

`RoutingPolicy` consumes immutable snapshots and returns one typed command or no command. Reconciliation destination, start, deadline and acceptance are one record; owner continuity is another record. The legacy test-only recovery mode and independent command booleans are removed. All safety checks count reconciliation as an action, and unknown evidence shares its fixed deadline.

`RouterInCallService` executes the chosen live endpoint on its serialized owner thread. Request generations reject obsolete outcomes. A single session-finalization function records both normal disconnect and lost-service outcomes. Failure is latched synchronously before queued evaluation, so an immediate pause or teardown cannot erase it. Explicit wrong-audio reports outrank positive platform observations.

`CallAudioStatus` owns the live assessment used by the UI: no observed call, waiting for answer, unknown, no Bluetooth audio, another device, disagreement, matching target observations, or user-reported problem. Configuration and request acceptance never turn into audio success.

The existing bounded, independent HFP/identity/authorization/safety workers are retained. Replacing them with synchronous reads or unbounded coroutine launches would reintroduce stalls. The projection adapter retains UNKNOWN separately from DISCONNECTED; the AndroidX query implementation inspected maps several failed/empty observations to disconnected. A library substitution alone would weaken that distinction.

The service still coordinates substantial diagnostics and lifecycle code. This change simplifies decision and result ownership without claiming that its entire architecture is finished. Diagnostic writes use a bounded serial queue of 256 jobs. Saturation drops file writes with an exported loss count and rejects exports visibly; it never executes storage work on the routing thread. Blocked-writer tests check bounded admission, ordering and rejection. Diagnostic content does not supply routing evidence.

## Verification and confidence

The production-service harness models Telecom display and HFP ownership independently. Its same-endpoint command returns success without changing either state. The transport matrix covers ordinary and stale-selector starts, 80/300/800/1400/2100/2400 ms transitions, both endpoint/audio callback orders, request echoes, configured/unconfigured competitor, and a permanently broken target transition: 192 combinations. The stale-selector path must issue owner then target, never target/owner/target. Broken transitions terminate without loops.

Additional regressions cover manual operation without projection, accepted-but-unobserved reconciliation, late completion after expiry, changed/ambiguous owners, protected requests, overlapping manual actions, immediate rejection followed by pause/teardown, call removal, and explicit physical-audio complaints. Core tests cover 288 reconciliation deadline/freshness/outcome combinations, 1,536 status combinations, full eligibility matrices and seeded state/callback exploration. Existing monitor, Android unit, Python analysis, lint and APK checks remain release gates; coverage thresholds are unchanged.

These are deterministic JVM tests of production code with framework doubles, not an Android emulator or physical Bluetooth test. They can establish command bounds, evidence handling and reporting. They cannot establish audible output, remote microphone reception, RF behavior, vendor admission or handover gaps.

Real-device qualification must use the newly installed, unchanged APK while parked: incoming/outgoing calls, manual choices during startup/reconciliation, reconnect, idle/wake, multiple-call protection and a long call. Record local speaker and remote microphone observations alongside the same-call trace. A platform-positive trace never overrides a physically wrong route. Compatibility remains unqualified until those cases pass.

### Local validation result

The final JDK 17 run passed 108 core test methods, 319 production-service scenarios (including the 192 transport combinations), 61 Bluetooth-monitor tests, 36 call-safety tests, 20 Android unit tests and 71 Python tooling tests. Core coverage was 98.71% line / 92.13% branch; service coverage was 97.83% line / 85.04% branch. The full Android build, formatting checks and existing coverage gates passed. Android lint reported no new issues; its existing baseline remains. APK signature, package, beta.21 version and SDK metadata verification passed locally; published updates use the separate protected CI signing key.

The supplied older capture was reanalyzed: its three substantive calls all show a displayed-target/other-HFP mismatch and none qualifies as PASS. Older incomplete and malformed sessions remain visible; no history was relabeled as a successful physical call. No Android device is attached to this environment, and hardware emulator acceleration is unavailable. No physical or emulator test is claimed.
