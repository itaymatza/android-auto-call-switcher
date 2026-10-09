# Dialing-time routing: evidence and qualification

## Conclusion

Moving the bounded outgoing-call request into DIALING is a plausible experiment,
not a verified seamless-routing fix. Beta.14 enables a bounded outgoing DIALING
phase and answer revalidation. Incoming RINGING remains separate: no routing request
is issued while an incoming call rings. Deterministic simulations qualify policy
and service behavior; Samsung/BMW physical audio still requires a device test.

## Public Android evidence

- [CallAudioManager](https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/CallAudioManager.java)
  groups CONNECTING, DIALING and ACTIVE for call-audio management. DIALING also
  starts ringback. This supports considering routing before the remote party answers.
- [CallEndpointController](https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/main/src/com/android/server/telecom/CallEndpointController.java)
  resolves the Bluetooth address from an offered endpoint and delegates to call
  audio routing without an ACTIVE-only test. Another request replaces the pending
  operation. An already-current route/address returns success without a new switch.
- [InCallService](https://developer.android.com/reference/android/telecom/InCallService#requestCallEndpointChange(android.telecom.CallEndpoint,java.util.concurrent.Executor,android.os.OutcomeReceiver))
  requires a platform-offered endpoint. The public method describes an endpoint
  change, not uninterrupted physical speaker/microphone audio.
- [BluetoothHeadset source](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/57b823f4f7/framework/java/android/bluetooth/BluetoothHeadset.java)
  implements legacy priority through profile connection policy. The connection-policy
  APIs are hidden/system APIs with privileged permission requirements; they are not
  a supported app-level preference weight among already-connected call devices.

These are AOSP/public SDK observations, not Samsung Android 17 or Android Auto
implementation guarantees. Android Automotive source describes a different product
from phone-projected Android Auto. No public source reviewed establishes how the
user's projection host arbitrates BMW versus the head-unit HFP route at answer time.

## Added observations

Call boundary snapshots are emitted synchronously at addition, state changes and
removal, including monotonic elapsed time from addition and first ACTIVE callback.
They retain cached evidence age, HFP device/audio sets, endpoint revision, target and
competitor configuration, and explicit priority/physical-audio limitations. Cache
contents must be interpreted with hfpFresh; a logged device is not necessarily current.

While projection is confirmed or a manual session is requested, boundary checks
prewarm asynchronous HFP/audio observations during pre-active states. Audio inventory
records framework IDs/types, source/sink capabilities, salted address aliases where
available, the reported communication device and available communication devices.
Inventory events are emitted only on change. Device presence and source capability
do not identify the microphone actually carrying cellular audio. Names are omitted.

## Required qualification before an early-routing default

1. Verify target identity, offered endpoint, fresh HFP connection, safe single SIM
   call and projection before the request; preserve all existing safety gates.
2. Test DIALING to ACTIVE, immediate answer, long dialing, busy/rejected/unanswered,
   immediate hangup, endpoint replacement and service rebind.
3. Measure time from DIALING and ACTIVE to the request and stable target HFP audio.
   Test physical incoming audio and microphone separately on the actual phone/car.
4. Check whether the target remains selected across answer-time external requests.
   Preserve manual routing and a bounded request budget; do not create a routing fight.
5. Extend low-overhead observation across the call, explicitly recording stale data,
   observation gaps and what is unobservable. The existing short verification watch
   remains a limitation in this diagnostics patch.

## Validation scope

The deterministic service harness covers dialing snapshots and an ACTIVE-to-disconnect
race in addition to existing cases. It uses framework doubles. Full Android build/lint
and real-device qualification are separate requirements.

## Prototype simulation findings

`DialingRoutingPrototypeTest` applies a candidate DIALING eligibility translation to
the production policy, independently of the production service. Seven deterministic
tests cover pre-answer confirmation, immediate answer, early disconnect, long dialing,
answer-time takeover, missing prerequisites and a competing request. They do not model
the Android Bluetooth implementation or enable early routing in the app.

Two reproduced limitations reject simply moving the existing transaction earlier:

- Its four-second action deadline can expire during long dialing if target SCO is
  not available until answer. Later audio cannot complete that failed transaction.
- A policy released after pre-answer confirmation does not reassert if another device
  owns audio at answer. Its `verified` flag records past confirmation, not current audio.

The next candidate needs separate bounded dialing and answer verification phases,
explicit current-audio evidence, preserved manual control and no unlimited retries.
An immediate answer can still precede completion of Bluetooth setup. These are
algorithm findings under simulated inputs, not a promise of real-world timing.
# Export investigation coverage

The export includes a versioned investigation guide linking research questions to
historical events. Export-time target/competitor aliases and the enabled setting
are labeled separately from call-time boundary snapshots. The guide distinguishes
unavailable public APIs, unobserved intervals, and physical audio that has not been
verified. It does not infer a disabled profile from a disconnected device.

Additional read-only evidence includes Bluetooth adapter state on HFP refreshes
and AudioManager microphone-mute state in audio inventory observations. Queries
remain on the existing bounded workers. No device names or raw addresses are
added. These observations do not identify the physical microphone in use.

The app still cannot directly inspect saved Phone calls/Media audio switches,
arbitrary Bluetooth weights, Android Auto's internal settings or the external
caller responsible for another endpoint request. Reconnect/profile-reset and
physical handover questions require device configuration evidence or a controlled
device test. Current polling is bounded, so the export explicitly labels gaps
rather than implying uninterrupted monitoring.

Validation of this increment: whitespace check and source review. Android APK
build/lint remain unverified because Android plugin resolution failed earlier;
the previous 80 service simulations do not exercise these framework probes or
the real export writer.

## Beta.14 implementation and qualification

Outgoing calls use one dialing transaction and, if needed, one answer transaction.
An outstanding dialing request is observed across answer without replacement.
A completed or failed dialing transaction receives fresh answer evidence and can
use one remaining automatic request. Explicit competing requests or protected
speaker/handset/wired changes during dialing suppress automatic answer takeover.
External request identity is unknown: SELF is endpoint/time correlation, not proof
that a particular in-call service or person originated a callback.

Read-only HFP sampling continues every two seconds after the short startup watch,
including failed routing sessions. There is no continuous audio recording or proof
of the physical microphone. Fresh samples, maximum observed gaps, service shutdown
and the user wrong-audio marker qualify the final result. Manual checks preserve
prior failure and observation-gap evidence. Every trace event carries observed
call/dialing/answer elapsed times; duration ends at the first disconnect callback.
Late-bound timing is explicitly labeled rather than reconstructed.

AOSP CallEndpointController can accept an already-current Bluetooth endpoint
without performing a new audio switch. The October 8 failed calls demonstrate
why accepted requests and displayed endpoints cannot replace HFP verification.
A repeated same-endpoint request is not advertised as physical audio recovery.

## Final local validation for the beta.14 candidate

- Core: 77 JUnit tests passed; JaCoCo line coverage 98.96%, branch coverage 92.50%.
- Service: 94 deterministic scenarios passed; production service/adapter/settings/bridge
  line coverage 96.69%, branch coverage 84.41%. These execute framework doubles.
- Tooling: 44 Python tests passed, including call-relative metrics and legacy exports.
- Kotlin style, configured coverage gates, shell syntax, version consistency and
  whitespace checks passed.
- Local APK compilation could not resolve uncached Android Gradle plugin 8.13.2.
  Android build, Android unit tests, lint, CodeQL and signed APK verification remain
  pending repository CI. No APK or real-device qualification is claimed.
