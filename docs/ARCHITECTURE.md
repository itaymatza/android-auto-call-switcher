# Architecture

## Design objective

**Android Auto Call Switcher** is an Android 14+ reference application that can make a deliberately bounded request to route an eligible call to a user-selected Bluetooth call endpoint. It is not a dialer, does not own the call lifecycle, and does not promise that a request will be honored. A successful `requestCallEndpointChange()` outcome means only that Telecom accepted the request. `onCallEndpointChanged()` verifies Telecom's displayed endpoint; this app requires matching target HFP/SCO evidence before declaring physical call-audio success. [1]

The design treats autonomous call-audio routing as a safety-sensitive feature. Its default posture is **fail closed**: when it cannot establish that a condition is safe, it makes no request or stops the current session.

## Components

The repository separates platform-independent behavior into the pure-JVM `:core` module. The
Android `:app` and framework-double `:verification:service-tests` modules both depend on that same
compiled implementation, preventing either harness from maintaining a private copy.

| Component | Responsibility | Safety boundary |
|---|---|---|
| `MainActivity` | Collects runtime permission, device selection, master-toggle, manual-test, pause, and diagnostic-export choices. | User actions are explicit. It cannot enable automation without a configured target and granted prerequisites. |
| `RouterSettings` | Persists the enabled state plus the locally chosen target and optional competing Bluetooth devices in app-private preferences. | No device identifiers are compiled into the source tree or sent over the network. |
| `Access` | Checks runtime permissions and the protected ongoing-call authorization. | Missing or revoked access stops routing eligibility. The displayed AppOps command derives from the installed application ID. |
| `ProjectionMonitor` | Reads the AndroidX host-provider state for optional projection gating. | Missing or unknown evidence freezes requests; confirmed loss stops an active guard. It does not infer state from names, Wi-Fi, or process activity. |
| `HfpMonitor` | Tracks the target device’s HFP connection and SCO state through the Bluetooth profile service. | It starts only for verified projection or an explicit manual test, stops on confirmed projection loss, and re-queries profile state rather than trusting broadcast extras. |
| `CellularClassifier` and `CallSafety` | Determine whether the call is a single, verifiable, non-emergency SIM-backed call. | Emergency, hidden/unclassifiable, external, self-managed, conference, and multi-call cases are rejected. |
| `RoutingPolicy` (`:core`) | Pure Kotlin state machine that decides whether the one-shot routing transaction is permitted, delayed, verified, or stopped. | Waits for call-start routing to settle, permits one target request per dialing/answer phase, with at most two automatic target requests per call, requires stable target HFP/SCO audio, and can restore a provably stale Samsung selector once. |
| `RoutingTrace` | Emits versioned, redacted, session-sequenced environment, evidence, action, and result records with stable reason codes and elapsed timings. | Places Telecom's displayed route beside exact HFP/SCO ownership without addresses, names, call identifiers, or phone numbers; it does not claim physical microphone quality. |
| `EndpointIdentity` and `AddressedTelecomRouter` | Resolve the saved paired target against live API 34+ endpoints and issue the request. | Uses only callback-supplied endpoint objects. Current selected-device names/aliases must map uniquely without a connected peer-name conflict, or a one-to-one HFP topology is required; ambiguity fails closed. |
| `RouterInCallService` | Coordinates Telecom, route, endpoint, projection, HFP, and call callbacks on the main thread. | Safety-relevant events are latched before deferred evaluation so that later callbacks cannot erase them. |

## Decision flow

1. Telecom binds `RouterInCallService` and the service starts its lightweight projection observer. HFP observation starts only after projection is verified or a parked manual test is requested. The service normally waits for a fresh pre-active-to-active call transition; a new call first observed as active can use the stricter late-bind evidence path.
2. The service gathers a current snapshot: authorization, runtime permissions, call safety, number of live calls, projection state, target availability, displayed Telecom route, and actual target HFP/SCO ownership.
3. `RoutingPolicy` rejects the snapshot unless all automatic-mode prerequisites hold. A manual one-shot bypasses only the master-toggle and projection checks.
4. After at least 300 ms of outgoing DIALING or incoming ACTIVE settling, extended by call-start activity up to 900 ms, `AddressedTelecomRouter` submits the exact current target callback object once, even when Telecom already displays target but actual SCO belongs elsewhere.
5. A matching endpoint produces diagnostic `TELECOM_ENDPOINT_CONFIRMED`, but neither an accepted outcome nor the endpoint display proves that physical call audio moved.
6. Only the exact configured Bluetooth address owning HFP/SCO in distinct fresh samples spanning the confirmation interval produces `TARGET_HFP_AUDIO_CONFIRMED` and releases the transaction.
7. The transaction never retries or reasserts target. API 37 endpoint-request callbacks are recorded; call-start requests can extend the bounded settling window, and an external request after the target request suppresses selector recovery. The callback cannot establish who requested a route. Manual Dialer changes remain system-owned.
8. If the transaction expires in the exact captured split state—Telecom displays target while the configured Android Auto endpoint still owns SCO—the app makes one bounded selector-recovery request for that already-active Android Auto endpoint. This reconciles Samsung's display with physical audio so target becomes selectable again. It is not a second target attempt and is never repeated. A subsequent speaker, handset, wired, or other-Bluetooth route suspends the policy; an already-submitted platform request cannot be recalled.

Every active session starts with `SESSION_ENVIRONMENT` and emits a deduplicated
`EVIDENCE_SNAPSHOT` whenever routing inputs or the resulting policy decision change. These records
capture app/OS build context, process and battery state, projection, call eligibility, HFP topology,
SCO-owner classification, endpoint revisions and resolution bases, route, phase, request budget,
and pending action. Request and selector-recovery records then carry generation, latency,
pre/post-route, and HFP-owner context, making callback order reconstructable without raw device or
call identifiers.

## State machine

`RoutingPolicy` uses the following phases.

| Phase | Meaning |
|---|---|
| `IDLE` | No call session is active. |
| `WAITING` | A fresh active call was observed; prerequisites are being checked. |
| `VERIFYING` | A bounded routing request was submitted and callback evidence is awaited. |
| `STABILIZING` | Target HFP/SCO audio was observed and must remain stable for the confirmation interval. |
| `RECOVERING_SELECTOR` | target audio failed in a verified split state; one Android Auto display-restoration request is pending so manual target selection remains possible. |
| `RELEASED` | Stable target HFP/SCO audio was confirmed; user and system routing own the remaining call. |
| `SUSPENDED` | A user choice or safety-relevant event stopped further requests for this call session. |
| `FAILED` | The bounded window or request budget expired, or Telecom threw an exception. |

## Automatic versus manual mode

The master toggle is off by default. Automatic mode requires the projection-host gate and an observed outgoing DIALING or fresh ACTIVE transition. The manual one-shot exists for a parked, controlled test while the service is already bound. It retains Telecom authorization, runtime permission, SIM/non-emergency classification, single-call, target-availability, and target-identity checks. It performs no more than one target request in its verification window. A failed split-brain transaction may additionally perform the single selector-recovery request described above.

## Platform compatibility

The application compiles and targets stable API 36 and requires API 34 or later. It declares API
37's exact `onCallEndpointRequested(CallEndpoint)` virtual signature, which is enforced by the
service harness while the API 37 platform package is unavailable to hosted SDK Manager builds.
Android 17 can dispatch the method; API 34–36 safely ignore it. Telecom may report an initiating
request before or after the resulting endpoint change, so self-request markers are generation-bound
and cleared at session teardown. External request callbacks are diagnostic only: the public callback
identifies another `InCallService` request, not whether a human intentionally selected it, and
Samsung emits such callbacks during startup.

`CallEndpoint.identifier` is unique on the device but the public contract does not promise a persistent Bluetooth-address identity, and AOSP keeps the Bluetooth address mapping inside Telecom. The app therefore never persists the identifier. It maps the saved paired device to each live callback set using a unique endpoint label, with a one-endpoint/one-HFP-device fallback. This is intentionally conservative and OEM-dependent.

## Non-goals

This implementation does not replace the default dialer, manage media profiles, disconnect Bluetooth, alter A2DP, record calls, read contacts or call logs, or upload telemetry. It is not an emergency-routing system, a driving-safety product, or evidence that an Android build, dialer, headset, vehicle, or projection host will accept the request.

## References

[1]: https://developer.android.com/reference/android/telecom/InCallService "Android Developers: InCallService reference"

## Beta.13 evidence and responsiveness

HFP queries and optional audio-framework probes run through `SingleFlightQuery` on separate workers. At most one query per monitor may be outstanding. Proxy invalidation and lifecycle shutdown discard obsolete completions without queuing replacement work behind a stalled Binder call. Evidence is timestamped at query start and expires after 750 ms. Confirmation requires distinct positive sample timestamps spanning the stability interval; a long observation gap resets it.

Before the first target request, disconnected or unknown projection can be rechecked within the existing ten-second evidence deadline. Requests require a positive provider result. Confirmed projection loss after submission still ends the transaction. Sampling retains the earliest pending deadline across callbacks.

Target confirmation is historical. Current target-audio evidence can become unknown or absent without reasserting routing. A watchdog firing well after its observation deadline records incomplete observation rather than classifying a teardown sample as in-window failure. Corroborated takeover requires fresh alternative SCO ownership and a 500 ms grace interval; immediate call shutdown is not counted as instability. Event-driven HFP monitoring continues while projection is active, with no full-call high-frequency poll.
