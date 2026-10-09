# Call audio across Android Auto and a separate car Bluetooth endpoint

Research updated 2026-09-26. This describes a projected Android Auto head unit and a second, native car HFP device connected to the **phone**. Android Automotive OS runs on the car and has a different HFP client architecture; its IVI guidance cannot be used as the phone-side routing contract.

## Ownership and evidence

| Layer | What it does | What this app can observe | What a positive signal means |
|---|---|---|---|
| Android Auto projection | Exposes a phone-driven car UI, including call controls | Projection presence and call-start callback ordering | Projection exists; does not identify the phone-call audio endpoint |
| Telecom | Manages the active call and available `CallEndpoint`s | Requested, available and changed endpoints; request outcome | A request is accepted, or the displayed endpoint changed; neither proves the Bluetooth voice link |
| Bluetooth HFP | Pairs the phone Audio Gateway with a car Hands-Free device; control and voice connections are distinct | Connected devices and `isAudioConnected(device)` from the Headset profile | Exact configured device reports voice audio; stable samples strengthen evidence |
| Audio framework / HAL | Selects communication output, mode and audio patch; optionally manages SCO on Android 17+ | Public `AudioManager.getMode()` and `getCommunicationDevice()`; **type only** | Diagnostic context, not a reliable mapping from device type to selected-device identity or physical speaker/microphone |
| Car multimedia | Could receive media over A2DP or projection while voice uses HFP | No authoritative physical audio telemetry from this APK | Must be checked on a parked car, with a listener confirming the microphone |

HFP 1.10 specifies remote phone control and voice between a phone and a hands-free unit. Its control connection is not proof that call audio is active. Telecom explicitly says API 37 `onCallEndpointRequested` is provisional and the actual endpoint can differ. Android recommends changing call endpoints through Telecom, and warns that using `AudioManager.setCommunicationDevice` or `startBluetoothSco` during a Telecom call causes audio issues. [Bluetooth SIG HFP](https://www.bluetooth.com/specifications/specs/hands-free-profile-1-10/), [InCallService API](https://developer.android.com/reference/android/telecom/InCallService), [Telecom audio endpoint guidance](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom).

Android 17 can use **Audio Managed SCO**, where an active stream patched to a SCO device and an audio mode drive SCO establishment. The feature is conditional on device property and HAL support; an Android 17 SDK value alone does not show it is enabled on a particular Samsung build. Public `getCommunicationDevice()` reports a selected communication device, but its Bluetooth type alone cannot distinguish two HFP peers. The app records mode and type as read-only context, never treats them as proof or requests a route through them. [AOSP Audio Managed SCO](https://source.android.com/docs/core/audio/sco-audio-mgmt), [AudioManager API](https://developer.android.com/reference/android/media/AudioManager).

## What the captured Samsung call shows

The owner-supplied redacted trace from 2026-09-26 shows an Android Auto UI outgoing call with two simultaneous HFP peers. On one call, the target briefly owned SCO during DIALING and the other peer owned it at ACTIVE. A target endpoint request was accepted in roughly 5 ms; Telecom displayed BMW, but the target SCO did not recur within the four-second observation window. A previous call had established target SCO in about 0.7 seconds. These observations support an intermittent endpoint/audio mismatch. The trace cannot tell whether the other HFP address is the projection unit, prove the physical speaker or microphone, identify whether AMSCO is enabled, or distinguish delayed audio stream setup from a Bluetooth/OEM route failure.

## Decision rules and bounds

1. Require a fresh safe cellular ACTIVE call, one live call, authorization, projection evidence for automatic mode, exact target HFP connection and a currently available uniquely resolved endpoint. Do not auto-route emergency, ambiguous, late-bound user-owned, conference, or multi-call sessions.
2. Allow short call-start activity to settle, capped at 900 ms. A pre-ACTIVE SCO flicker is not confirmation of sustained ACTIVE audio.
3. Submit at most one Telecom target request. A successful request callback is not a route or audio success verdict. Re-query exact HFP audio on callbacks and every 250 ms during the bounded four-second verification period; require 250 ms of stability. After confirmation, passively sample for three more seconds to detect an unannounced HFP takeover. This watch does not issue another route request.
4. Once the request is submitted, a replacement Telecom endpoint list does not invalidate exact, stable target HFP audio. It **does** prohibit a new request. This handles asynchronous endpoint snapshot churn without guessing a new UUID.
5. Respect a competing in-call service's request and protected user route; never reassert after the one target request. Selector recovery is a separate, bounded one-shot UI repair only when exact target audio is absent and the competing endpoint actually owns HFP audio.
6. Stop periodic sampling at the terminal decision, call removal or service teardown. A failure means the target was **not observed** in the window, not that its speaker never played sound. Post-window device testing is required.

## CI scenario matrix

| Scenario | Expected contract | Coverage |
|---|---|---|
| AA UI outgoing DIALING → ACTIVE, early SCO flicker | Wait for ACTIVE and quiet; one request maximum | Service harness |
| Incoming, connecting, rebind, delayed authorization/projection | No unsafe takeover or duplicate request | Service harness and core property suite |
| Telecom target display but other HFP owns SCO | No false audio confirmation; bounded verdict | Service harness and trace analyzer |
| Telecom endpoint snapshot changes after request, target SCO arrives | Confirm exact stable HFP audio without retry | Service harness |
| HFP owner changes without a broadcast during call-start settling | Re-query HFP every 250 ms within the existing 300–900 ms cap and extend the quiet period when the owner changes | Service harness |
| Delayed audio-mode/communication-device transition | Log context, await target HFP evidence, no AudioManager mutation | Service harness |
| HFP broadcast missing but proxy changes | Timer re-queries HFP before the deadline | Service harness |
| Target audio confirmed, then another HFP peer silently takes over | Bounded passive watch records confirmation loss; no reassertion or long-running polling | Service harness and trace analyzer |
| Request timeout, callback race, external request, protected route | One request; no routing fight | Service harness and seeded core property suite |
| Long unsuccessful verification and terminal idle | Approximately 16 periodic HFP queries in four seconds; no subsequent periodic work | Deterministic service query budget |
| Two similar Bluetooth endpoint names, UUID churn, other peer disconnected | Resolve uniquely or fail closed | Core endpoint tests and service harness |
| Actual voice, microphone, AA navigation/media, Bluetooth call-device variations | Need parked, authorized physical tests; no emulator or host stub can certify | Device qualification matrix in `TESTING.md` |

The deterministic query budget is a CPU/IPC work bound, not a wall-clock benchmark. CI runs host contracts on JDK 17 and 21, Android compilation/lint, and coverage gates. For OEM behavior, record fresh trace, app diagnostics, exact APK hash, phone build and manual speaker/microphone observations. Do not label an unobserved physical route a passing product test.
