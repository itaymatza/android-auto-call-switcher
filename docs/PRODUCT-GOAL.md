# Android Auto Call Switcher: product contract and review

The user chooses the Bluetooth device for cellular call speaker and microphone audio. Android Auto
continues handling navigation, media, and its other functions. The device may be earbuds, a headset,
a speakerphone, or a vehicle system. Car-brand names must never select a route or authorize a request.

## Implementation fit

| Requirement | Implementation | Verification / remaining limit |
| --- | --- | --- |
| Use the chosen Bluetooth device | Paired Bluetooth address is local identity; current device name/alias resolves a current Telecom Bluetooth endpoint. | Renamed headset, name/alias, non-car target, and duplicate connected-name tests. Ambiguous identity blocks requests. |
| Leave Android Auto's other functions alone | `requestCallEndpointChange()` is the routing write. `AudioManager` is queried only for diagnostics. | No media, A2DP, mute, Bluetooth connection, or global communication-device mutations. Physical AA/media continuity still requires a device test. |
| Run automatically alongside Android Auto | Verified AndroidX projection gate; outgoing dialing and incoming answer triggers; bounded late-bind recovery. | Initial projection/device connection gaps wait only within the startup window; loss after a target request prevents further action. No guess from Bluetooth names or Wi-Fi. |
| Preserve the user's Phone app | Non-UI `InCallService`; no dialer role request. | One-time protected Telecom authorization remains required. Installation alone cannot grant this access. |
| Respect manual choices | Protected handset/speaker/wired callbacks after answer or during dialing latch suspension before queued evaluation. | Rapid protected-route change followed by a platform revert does not permit a new target/recovery request. Already-submitted platform requests cannot be recalled. |
| Do not fight other route controllers | At most one target request per dialing/answer phase and two automatic target requests per call; one tightly gated selector recovery. | Endpoint request callbacks are observational and cannot identify the requesting user/app. Other Bluetooth choices can be indistinguishable from Android Auto startup actions. |
| Report success honestly | Current exact-address HFP/SCO samples must corroborate the selected device for the stability interval. | Telecom success or displayed selection alone does not verify physical microphone/speaker operation. |
| Avoid stale endpoints | Requests require the exact object in the latest available-endpoint callback. | Old object with reused UUID is rejected. Late result callbacks remain session-generation checked. |

## Validation

Core and production-service deterministic tests exercise the real routing implementation with
framework doubles; they do not certify physical Bluetooth audio. Android build, unit tests, lint,
APK manifest/signature verification, and tooling checks are required before publication.

Local validation on 2026-10-09: 83 core JUnit tests, 101 deterministic service scenarios, 10 app
unit tests, and 45 Python tooling tests passed. The core and service coverage gates passed. The
Android debug APK assembled; Kotlin style checks, Android lint, and APK signature/manifest checks
passed. No real-device LE Audio, VoIP, microphone, speaker, or AA media-continuity qualification
was performed by these host checks.

## Foreground compatibility and physical test workflow

The Test safely card now inspects current classic HFP, LE Audio, and hearing-aid profile
connections without changing any route, connection, or permission. It checks the selected
address only. A dual-transport device with classic HFP connected remains eligible for the
existing service checks. An observed LE/hearing-aid connection without observed HFP gets an
explicit unsupported-transport explanation. Missing proxies, stale/failed queries, and revoked
access remain unknown; absence is not inferred from an unavailable profile. This preflight
never authorizes routing or certifies active audio. State changes enter the redacted log as
`DEVICE_PREFLIGHT` with connection-only evidence. It runs only while the setup activity is
started, uses a bounded single-flight worker, and expires old observations.

“Record parked call test” asks separately about the selected speaker, the selected microphone
(confirmed with the other person), Android Auto navigation during the call, and media resumption
afterward. Each answer is PASS, FAIL, or NOT_CHECKED. Canceling drops an incomplete report;
changing device configuration drops it too. Completed reports enter the redacted log as
`USER_PARKED_TEST` with `source=user_report` and session timestamps. They never upgrade an
automatic HFP result or qualification-tool verdict. Export the report alongside a captured run
and explicitly complete the existing operator observations. A passing report applies to that
test only, not every phone, transport, or future call.

## Remaining product gaps

1. **LE Audio-only and hearing-aid transports.** The routing monitor observes classic HFP/SCO only; the foreground preflight can now identify current LE/hearing-aid connections and explain the limitation.
   The generic Bluetooth endpoint type does not prove which transport is active. A future backend
   must resolve LE device/group identity and represent its evidence separately. It must not relabel
   Telecom's route display or `AudioManager`'s device type as exact physical audio proof. Supported
   LE group lead devices can change, so a saved earbud address is not automatically a stable group
   identity. Keep these cases explicitly unsupported until the backend and device tests exist.
2. **All voice apps.** Only verifiably SIM-backed, non-emergency cellular calls are eligible. VoIP
   and self-managed app calls are not covered by the current classifier/service contract. Supporting
   them needs a separate capability review rather than weakening the cellular safety gate.
3. **Phone-only first setup.** `MANAGE_ONGOING_CALLS` is protected. The current utility cannot obtain
   it from a normal permission dialog while preserving the existing default dialer. Do not claim
   that a car association, extra runtime permission, or an in-app button completes this grant.
4. **Universal reliability.** Host tests verify logic; they cannot certify OEM Bluetooth behavior,
   call input/output, or AA media continuity. Qualify each phone/OS/head-unit/target setup with the
   unchanged APK. The initial Samsung/BMW result is proof of concept, not a brand support list.

## Platform references

- [InCallService](https://developer.android.com/reference/android/telecom/InCallService): endpoint
  requests use callback-supplied endpoints; endpoint-request observations are not final changes.
- [CallEndpoint](https://developer.android.com/reference/android/telecom/CallEndpoint): public
  identity consists of an endpoint name, type, and UUID, not a Bluetooth address.
- [BluetoothHeadset](https://developer.android.com/reference/android/bluetooth/BluetoothHeadset):
  classic headset/hands-free profile and per-device audio connection observation.
- [BluetoothLeAudio](https://developer.android.com/reference/android/bluetooth/BluetoothLeAudio):
  connected devices and group lead identity are distinct from classic HFP/SCO evidence.
- [AOSP CallAudioRouteController](https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/main/src/com/android/server/telecom/CallAudioRouteController.java):
  classic, hearing-aid, and LE call routes share Telecom's Bluetooth route category. Framework
  route state is useful diagnostic context, not independent transport or physical audio proof.
