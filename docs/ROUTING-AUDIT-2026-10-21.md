# Fresh routing audit — 2026-10-21

## Scope and acceptance criteria

Review baseline: `36d37e31de388ed5593927dd70902e9e3fa428a8` (beta.21). This is a new audit of that implementation, not an assumption that the previous redesign solved the physical problem.

The product must move an eligible cellular call to the saved native-car HFP device while leaving Samsung Phone and Android Auto navigation/media intact. It must respect a later route choice, bound its own commands, and accurately distinguish configuration, accepted commands, current observations, and human confirmation. No amount of code coverage establishes audible output or remote microphone reception.

The available private capture is beta.18. It contains accepted requests while the preferred endpoint was displayed without matching audio observations, plus later selector/HFP disagreements. Some request-time samples report no SCO owner at all. That is not evidence that a particular alternative device owned audio at every instant. There is no newer physical beta.21 trial in the available evidence. Private identifiers and call timestamps are excluded from this repository.

## Platform contracts, checked again

| Question | Primary source and conclusion | Design consequence |
| --- | --- | --- |
| Does accepted mean audio moved? | [AOSP CallEndpointController](https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/main/src/com/android/server/telecom/CallEndpointController.java) has an early success return when its route/address already matches; otherwise it calls `setAudioRoute`. | Keep command outcome separate from observation. Do not repeat an idempotent target request. This is consistent with the private trace, not proof of Samsung's internal implementation. |
| Can we bypass that with the old Bluetooth API? | [AOSP CallAudioRouteController](https://android.googlesource.com/platform/packages/services/Telecomm/+/9b9f5af7ee0a7dba65ba2dafd24e918a9aa22caa/src/com/android/server/telecom/CallAudioRouteController.java) also suppresses same-route changes; [InCallService](https://developer.android.com/reference/android/telecom/InCallService) deprecates address-based requests and explicitly warns about routing to a different Bluetooth device. | Retain current callback-supplied endpoints. No fallback API carousel. |
| Is an endpoint request a human action? | [InCallService.onCallEndpointRequested](https://developer.android.com/reference/android/telecom/InCallService#onCallEndpointRequested(android.telecom.CallEndpoint)) describes requests from an InCallService, not requester identity or final routing. | Correlation to our command remains inference. Protect identifiable subsequent choices and retain the unresolved attribution limitation. |
| Does false HFP audio prove disconnection? | [BluetoothHeadset implementation](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/0b8a3af3cc/framework/java/android/bluetooth/BluetoothHeadset.java) returns false on several service/error paths. | Unknown evidence cannot authorize recovery. Require a positive sole alternative owner for reconciliation; no owner is not proof of another owner. |
| Can another audio API provide exclusive cellular control? | [AudioManager.setCommunicationDevice](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)) prioritizes the audio-mode owner; `MODE_IN_CALL` requires the main telephony application's protected permission. | VoIP/CoreTelecom or an AudioManager request does not give this companion ownership of an existing cellular call. |
| Does replacing the dialer help? | [InCallService](https://developer.android.com/reference/android/telecom/InCallService) requires a complete in-call UI for the default-dialer role. [AOSP admission](https://android.googlesource.com/platform/packages/services/Telecomm/+/main/src/com/android/server/telecom/InCallController.java) separately checks permission/AppOps for non-UI services. | Preserve Samsung Phone. The existing advanced authorization path remains device-qualified, not a portable consumer entitlement. |
| What architectural change matters? | [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations) emphasize separation of concerns, data ownership and unidirectional flow. [Testing guidance](https://developer.android.com/training/testing/fundamentals/strategies) explicitly distinguishes test fidelity and hardware-dependent needs. | Separate evidence lifetime, bounded commands and passive observation. Add regressions against falsifiable behavior, not another framework for its own sake. |

Public `CallEndpoint` still lacks a Bluetooth hardware address. Unique live label/topology mapping is an inference, not a universal identity contract. Reconciliation is an explicit compatibility technique for a platform-state mismatch. It is justified only with fresh, stable, unique owner evidence and remains bounded to one owner command followed by one target command. No ordinary-app API found in this review removes those limitations while satisfying the product constraints.

## Defects and regression scope

The review targets failures that the large previous scenario count did not cover:

1. Audio stability history can survive an early return for unavailable prerequisites. A positive sample before a gap can be joined to a later sample and treated as one stable interval.
2. Owner stability for reconciliation has the same lifetime problem. A gap must restart the owner evidence interval, even if the same owner later returns.
3. Handler evaluation is coalesced. An unknown/changed-owner callback must invalidate history immediately; a later callback before evaluation must not erase that edge.
4. Manual routing without projection stops passive observation after its short confirmation watch. A positive displayed status can outlive its evidence.
5. A pending manual transaction must yield to a newer protected route choice. The incoming pre-answer period also needs this protection; it must not depend on an automatic session already having begun.

Before changing production logic, the two new policy tests and four new production-service scenarios failed against beta.21. Existing service scenarios passed. After the fixes, those six cases passed with the existing suites (113 core tests and 323 service scenarios at that checkpoint). The protected-choice and coalesced-owner matrix adds 14 further service scenarios. A further regression then demonstrated that loss of a runtime permission could stop observation while the UI retained a positive cached assessment; it failed before the timestamped-status fix.

## Implementation decision

Use one `SampleStability` component for target and alternative-owner history. It admits only fresh samples, resets on missing/future/expired evidence, and measures sample-to-sample time rather than elapsed wall time on a cache. Different owners, backward timestamps and sampling gaps start a new interval. A common prerequisite decision boundary clears history before returning for unavailable prerequisites, so individual early-return branches cannot preserve stale confidence.

Invalidations are also latched in the service's HFP/projection callbacks before queuing evaluation. This retains an unknown or changed-owner edge even if a later callback overwrites the latest snapshot before the reducer runs. It does not turn observations into new route commands.

Passive observation follows the lifetime of an active projected or manually routed call, including after the routing policy finishes or fails. Manual operation no longer depends on projection to refresh audio health. Protected endpoint changes stop further commands in pending manual transactions, and incoming pre-answer changes/requests are protected before the automatic session begins. Android can still complete a request already submitted; the companion cannot recall it. A new explicit Route now remains able to start a fresh bounded attempt.

`SessionBridge` now stores the status with the original HFP query timestamp. Each UI read expires sampled states to UNCONFIRMED after the same 750 ms freshness bound used by routing. Republishing an old sample cannot extend its lifetime. The service refreshes this evidence even when the displayed text is unchanged, notifying observers only for an actual text/status change. The activity already refreshes while visible. Explicit user complaints and no-call state are not erased by audio sample expiry.

The command budget, fresh endpoint identity requirement, current public API, independent bounded workers, protected authorization, and cellular/emergency/multiple-call gates remain in force. The changes remove duplicate temporal bookkeeping and correct ownership/lifetime boundaries; they do not add retries, new delays, profile toggles, or a second routing backend.

## Final local verification

The final production-code checks passed on JDK 17:

| Check | Result |
| --- | --- |
| Core policy | 113 tests, zero failures |
| Production service with Android framework doubles | 339 scenarios, zero failures; includes the existing 192 transport combinations |
| Bluetooth monitor | 61 tests, zero failures |
| Call-safety checks | 36 tests, zero failures |
| Android app unit tests | 20 tests, zero failures |
| Python build, trace and release tooling | 71 tests, zero failures |
| Kotlin formatting and coverage gates | Passed |
| Android debug build and lint | Passed; no new lint issues, one existing baseline-filtered error |
| APK identity/signature verification | Passed; package `org.carcallrouter.companion`, version code 24, version `0.3.0-beta.22`, minimum SDK 34, target SDK 36 |

Measured line/branch coverage: core 98.1%/92.5%, service 97.9%/86.0%, Bluetooth 92.2%/84.1%, call safety 97.1%/90.8%. These are coverage of the exercised production code under host-side tests, not probabilities of successful physical calls. CI additionally checks the host suites on JDK 17 and 21 and builds the distributable APK with the protected beta signing key. The locally signed APK is not the upgrade artifact.

## Qualification boundary

This work demonstrates software defects and their correction in deterministic production-code tests. Local ADB found no attached Android device; the connected remote-device service also had no devices. Hardware emulator acceleration was unavailable. No emulator, physical Bluetooth, speaker, microphone, or RF test is claimed.

The remaining external uncertainty is specific: whether the newly installed APK and the Samsung/BMW/head-unit combination execute the requested handover, retain it, and carry both directions of audio. An emulator can test app/framework integration but cannot establish those car properties. A parked same-build trial must include an incoming call, outgoing call, manual switch, pre-answer/manual override, reconnect, and a longer call, with both local listening and remote microphone confirmation and the same-call trace. A physically wrong route overrides every positive platform indication.

The service still contains substantial lifecycle/diagnostic coordination. A wholesale rewrite or an untested extra API backend is not established to improve this hardware outcome. Further extraction should follow a demonstrated ownership problem, while device qualification determines whether this bounded companion is actually useful on the target combination.
