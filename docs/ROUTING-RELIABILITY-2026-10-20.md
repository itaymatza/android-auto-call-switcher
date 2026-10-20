# Selected endpoint and call audio can disagree

## Evidence and platform mechanism

Recent private device traces and the owner's physical observations exposed two separate problems:

- Telecom accepted a request for the preferred endpoint in milliseconds while sampled HFP audio remained on another device. The displayed endpoint was not reliable proof of the call's speaker or microphone.
- Endpoint-request notifications around answer were treated as possible manual choices; automatic routing could stop while the wrong owner remained active. Older recovery also depended on an optional competing-device setting and ended after restoring the selector, without completing the preferred-device transition.

No private trace, device alias, address, call timestamp or recording is included here. These observations motivate synthetic regression scenarios; they do not establish every cause inside the vendor Bluetooth stack.

The public AOSP implementation explains a concrete no-op mechanism. `CallEndpointController.requestCallEndpointChange` returns success immediately when `isCurrentEndpointRequestedEndpoint` says the endpoint is already selected. It does not invoke `setAudioRoute` on that path. The comparison uses Telecom's `CallAudioState`, which can disagree with separately sampled HFP ownership. The lower `CallAudioRouteController.routeTo` also has a same-route/same-address early return. Merely substituting the deprecated `requestBluetoothAudio` API would not reliably bypass this behavior.

Sources inspected on 2026-10-20:

- [AOSP CallEndpointController](https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/main/src/com/android/server/telecom/CallEndpointController.java): `requestCallEndpointChange`, `isCurrentEndpointRequestedEndpoint`.
- [AOSP CallAudioRouteController, pinned revision](https://android.googlesource.com/platform/packages/services/Telecomm/+/9b9f5af7ee0a7dba65ba2dafd24e918a9aa22caa/src/com/android/server/telecom/CallAudioRouteController.java): `routeTo`.
- [Android InCallService API](https://developer.android.com/reference/android/telecom/InCallService): endpoint change requests, their outcomes, and API 37 endpoint-request notifications.

This is a source-backed explanation consistent with the traces, not proof that Samsung ships identical internals. Request notifications do not identify a human gesture or the requesting service. Matching a recent local request is correlation only; a simultaneous external request for that same endpoint can remain ambiguous.

## Changed routing contract in beta.21

Routing still begins after answer and the bounded startup quiet window. The first target request gets four seconds for fresh, stable HFP confirmation. If it fails in the specific state where Telecom displays the target and a different sole HFP owner persists, one recovery sequence is allowed:

1. Resolve the observed owner to an exact current Telecom endpoint using fresh, unique live identity evidence. Require consistent owner samples spanning at least 500 ms, with no sample gap over 750 ms. If a competing device is configured, the observed owner must be that device.
2. Request that already-observed owner endpoint once. This reconciles the selector with the current audio owner; it does not guess another device, disconnect Bluetooth, bounce through speaker, or change media routing.
3. Require the expected endpoint plus fresh matching ownership sampled after reconciliation started, within 1.5 seconds. A successful request outcome alone cannot advance the sequence.
4. Request the target once more and allow four seconds for HFP confirmation. Stop afterward regardless of outcome.

The automatic call budget is two target requests and one reconciliation. Manual routing starts a separately explicit bounded transaction with the same eligibility and identity checks. It bypasses only the automation toggle and projection requirement.

This intentionally changes the former configured-competitor-only boundary. Without that setting, the app may reconcile only to the single positively observed audio owner. Ambiguous devices, duplicate labels, missing identity, multiple audio owners, stale samples or a changed owner cannot authorize recovery. New external request observations inhibit or interrupt recovery. Protected route choices, permission or authorization loss, unsafe/multiple calls, target disconnection and automatic projection loss stop action. Unknown evidence cannot authorize a command. An already-submitted Android request cannot be recalled.

## Reporting contract

The preferred-device label describes configuration. A separate live indicator distinguishes no call, waiting for answer, unknown evidence, no Bluetooth audio, another device, conflicting signals and matching target observations. Neither configuration nor request acceptance becomes audio confirmation. Stale or ambiguous samples cannot show matching target observations. A wrong-audio report takes precedence even if platform signals agree.

Known routing failure and user-reported failure survive disconnect, service teardown and incomplete observation. The offline analyzer also honors terminal failure flags when intermediate events are missing. Platform HFP confirmation remains a sampled observation, never a claim that the user heard the correct speaker or that the remote participant heard the correct microphone.

## Verification and limits

The production-service harness runs separate Telecom-endpoint and HFP-owner states. A same-endpoint request returns success without changing either state, reproducing the no-op. Its 64 transport combinations vary 80/300/800/1400 ms transitions, endpoint-first versus HFP-first callbacks, request echoes, competitor configuration and persistent target failure. Successful simulated transitions reach the target; broken transitions remain failed without loops. Other scenarios cover interruption during reconciliation, ambiguous identity, pre-answer owner replays, explicit wrong-audio reports, teardown and existing manual/safety regressions.

Core tests add 288 deadline/freshness/callback combinations and 1,536 live-status combinations. Existing seeded policy/callback exploration and production Bluetooth/call-safety harnesses remain enabled. Coverage gates are unchanged. The Android APK build, Android unit tests, lint and Python tooling contracts are part of the release checks.

These are deterministic JVM simulations of production service code with framework doubles, not an Android emulator or a real Bluetooth/car test. They provide strong evidence for bounded decisions, callback handling and honest reporting. They do not reproduce vendor firmware, RF conditions, actual audio transport, microphone selection or audible handover gaps.

The remaining acceptance test is a parked trial on the newly installed APK: incoming and outgoing calls, answer transitions, manual route changes during startup/recovery, reconnect, idle/wake, a second call, and a long call. Record both local speaker and remote microphone observations alongside a same-call trace. A platform-positive trace cannot overrule an observed wrong speaker or microphone. Any failure must remain a failed qualification case and become a new regression before compatibility is claimed.
