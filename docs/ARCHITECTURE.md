# Architecture

## Design objective

**Android Auto Call Switcher** is an Android 14+ reference application that can make a deliberately bounded request to route an eligible call to a user-selected Bluetooth call endpoint. It is not a dialer, does not own the call lifecycle, and does not promise that a request will be honored. A successful `requestCallEndpointChange()` outcome means only that Telecom accepted the request. `onCallEndpointChanged()` verifies Telecom's displayed endpoint; this app requires matching target HFP/SCO evidence before recording sampled target audio confirmation; physical speaker/microphone behavior requires a device test. [1]

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
| `RoutingPolicy` (`:core`) | Pure Kotlin state machine that decides whether the one-shot routing transaction is permitted, delayed, verified, or stopped. | Waits for call-start routing to settle, emits typed commands, permits one target request after answer, and reconciles a pre-existing selector/HFP mismatch before that request when fresh unique ownership permits. Requires stable sampled target HFP audio. |
| `RoutingTrace` | Emits versioned, redacted, session-sequenced environment, evidence, action, and result records with stable reason codes and elapsed timings. | Places Telecom's displayed route beside exact HFP/SCO ownership without addresses, names, call identifiers, or phone numbers; it does not claim physical microphone quality. |
| `EndpointIdentity` and `AddressedTelecomRouter` | Resolve the saved paired target against live API 34+ endpoints and issue the request. | Uses only callback-supplied endpoint objects. Current selected-device names/aliases must map uniquely without a connected peer-name conflict, or a one-to-one HFP topology is required; ambiguity fails closed. |
| `RouterInCallService` | Coordinates Telecom, route, endpoint, projection, HFP, and call callbacks on the main thread. | Safety-relevant events are latched before deferred evaluation so that later callbacks cannot erase them. |

## Decision flow

1. Telecom binds `RouterInCallService` and the service starts its lightweight projection observer. HFP observation starts only after projection is verified or a parked manual test is requested. The service normally waits for a fresh pre-active-to-active call transition; a new call first observed as active can use the stricter late-bind evidence path.
2. The service gathers a current snapshot: authorization, runtime permissions, call safety, number of live calls, projection state, target availability, displayed Telecom route, and actual target HFP/SCO ownership.
3. `RoutingPolicy` rejects the snapshot unless all automatic-mode prerequisites hold. A manual one-shot bypasses only the master-toggle and projection checks.
4. After ACTIVE and 300–900 ms of call-start settling, the policy plans one target transition. If Telecom already displays the target but another sole resolved HFP device owns audio, require 500 ms of consistent samples, reconcile to that owner once, and require the expected endpoint plus a strictly newer matching HFP sample within 2.5 seconds. Otherwise a normal transition needs no reconciliation.
5. Submit the single target command using the current callback-supplied endpoint. Allow four seconds for distinct fresh target HFP samples spanning 250 ms. An accepted outcome or matching endpoint alone is not audio confirmation.
6. Release after confirmation or terminate on failure. Never retry a failed target request. Unknown identity cannot authorize a command; observed external control and protected user choices stop further action. An already-submitted command cannot be recalled.
7. Publish the separate current `CallAudioStatus` while passive observation continues. A previous confirmation does not imply that the target remains observed now. Explicit user-reported failure takes precedence.
8. Finalize traces and persisted results through one `finishSession` path for both disconnect and service loss. Routing failure is latched synchronously before a queued evaluation can be superseded by pause or teardown.

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
| `RECOVERING_SELECTOR` | A pre-existing selector/HFP mismatch is being reconciled to the sole observed owner before the single target request. |
| `RELEASED` | Stable target HFP/SCO audio was confirmed; user and system routing own the remaining call. |
| `SUSPENDED` | A user choice or safety-relevant event stopped further requests for this call session. |
| `FAILED` | The bounded window or request budget expired, or Telecom threw an exception. |

## Automatic versus manual mode

The master toggle is off by default. Automatic mode requires the projection-host gate and a fresh ACTIVE transition after read-only pre-answer observation. The manual one-shot exists for a parked, controlled test while the service is already bound. It retains Telecom authorization, runtime permission, SIM/non-emergency classification, single-call, target-availability, and target-identity checks. It performs no more than one target request in its verification window. A pre-existing mismatch may require one reconciliation before that target request, under the same identity and freshness gates.

## Platform compatibility

The application compiles against stable API 37.0 with AGP 9.2.1 and Gradle 9.4.1, targets
API 36, and requires API 34 or later. The compiler verifies the public
`onCallEndpointRequested(CallEndpoint)` override. Host tests cover request origins and callback
orders; Android 17 platform dispatch still needs device evidence. Target API 37 behavior changes
are not opted into without qualification.
Android 17 can dispatch the method; API 34–36 safely ignore it. Telecom may report an initiating
request before or after the resulting endpoint change, so self-request markers are generation-bound
and cleared at session teardown. External request callbacks inform conservative control protection: the public callback
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

Target confirmation is historical. Current target-audio evidence can become unknown or absent without reasserting routing. A watchdog firing well after its observation deadline records incomplete observation rather than classifying a teardown sample as in-window failure. Corroborated takeover requires fresh alternative SCO ownership and a 500 ms grace interval; immediate call shutdown is not counted as instability. Event-driven HFP monitoring continues while projection is active, with 500 ms passive sampling during active projected calls.

## Bounded protected observations

SIM account and emergency-number reads, Telecom authorization, projection provider reads
(including cursor access and close), HFP observations and framework audio sampling use separate
process-wide single-worker executors with no queue. A stuck Binder read cannot block the service
owner, accumulate queued jobs, or create replacement threads. The owner receives immutable
results; closing a monitor discards later completions. Foreground setup authorization uses its
own process-wide zero-queue worker, so a stuck setup read cannot occupy the routing service lane.
Explicit user verification invalidates cached access and waits for a new result within a five-second
foreground deadline; it does not report a cached grant or denial as a fresh verification.

Call safety has explicit pending, safe and unsafe states. Permission and unsupported-call flags
reject immediately. Positive safety is bound to the exact Call object and account/telephone
handle, expires after 750 ms, and refreshes every 250 ms. State-only details updates retain fresh
evidence, while identity changes invalidate it. Unknown authorization and pending safety never
permit a request; the policy waits within the existing action deadline and suspends if evidence
is lost after a request. Projection updates invalidate in-flight observations by generation;
queries taking more than 750 ms cannot provide positive evidence. Stable projection evidence
remains event-driven rather than expiring merely because a connection stays unchanged.

### HFP observation and live identity

`HfpMonitor` owns three independent process-wide bounded, zero-queue workers: exact HFP
connection/SCO reads, live name/alias inspection and optional power diagnostics. Bluetooth
events and proxy lifecycle changes invalidate both cached and in-flight generations. An old
query drains its slot before a fresh read; invalidation never queues behind a stalled Binder.
Names and aliases remain local, expire independently after 750 ms, and must be complete for
multiple-device matching. Setup names do not authorize routing. Metadata cannot confirm SCO,
and its loss cannot turn an unidentified endpoint into a proven user/competitor selection.

## Fresh design review

See [the October 20 source review and routing decision](ROUTING-RELIABILITY-2026-10-20.md) for alternatives, inferred identity limits, typed command ownership, deterministic transport tests and the outstanding physical qualification boundary.

## Evidence lifetime and command lifetime

`SampleStability` owns consecutive fresh-sample history for both target confirmation and alternative-owner reconciliation. The policy checks prerequisites through one boundary and clears history before returning for unavailable evidence. The service also invalidates it synchronously on unknown HFP, owner changes and lost projection evidence, before coalesced evaluation can hide an intervening edge.

Passive call-audio observation is independent of the transaction phase. A manually initiated call transaction continues read-only polling without projection through completion or failure until the call ends. A later protected route choice stops further commands, including during incoming pre-answer observation; an already-submitted Android request cannot be recalled. Route-request correlation cannot establish human intent; exact attribution remains unavailable.

See [the fresh audit](ROUTING-AUDIT-2026-10-21.md) for defect reproduction, source contracts and qualification limits.

The UI bridge retains the query timestamp with its audio assessment and rechecks freshness on reads. Service publication updates this evidence even when the text has not changed. Cached observed statuses expire to UNCONFIRMED; they cannot be kept fresh by redraws or an old query being republished. User-reported problems retain their independent lifetime.
