# Changelog

## 0.3.0-beta.18

- Clear endpoint objects between calls and invalidate old request results when an explicit manual transaction starts.
- Preserve unfinished dialing requests through temporary evidence loss at answer; block overlapping Route now attempts.
- Protect resolved third-device Bluetooth changes and API 37 handset/speaker/wired/third-device requests.
- Treat multiple simultaneous reported HFP owners as ambiguous, never as verified target audio or safe selector recovery.
- Move process, power and previous-exit diagnostics off application/call-start callbacks; cache only bounded-age observations.
- Read paired-device names off the setup UI thread with a bounded timeout; discard results after leaving the screen.
- Persist incomplete observation when Telecom unbinds mid-call and reject parked reports spanning different calls.
- Stage releases as drafts, verify downloaded APK/digest/report consistency before publication, and preserve published assets on retry.
- Validate signed release tags against source versions, exclude commit-specific debug tags and keep beta releases prereleases.

## 0.3.0-beta.14

- Request the selected Bluetooth device during outgoing dialing; revalidate at answer with no overlapping request and at most two automatic target requests per call. Preserve explicit route choices.
- Continue bounded HFP observation throughout active projected calls, including routing failures; record gaps and late audio loss without an automatic routing fight.
- Add per-event dialing/answer timing, observed connected duration, request stage/budget, unknown callback source, and persistent user wrong-audio reports.
- Add Bluetooth adapter/mute/audio inventories and an export investigation guide that states unavailable settings and physical-audio limits.
- Expand deterministic service and core tests for early routing, pending requests at answer, long dialing, silent takeover, and callback ordering.

## 0.3.0-beta.13

- Run HFP and optional audio-framework queries on bounded single-flight workers; ignore invalidated results after proxy changes and teardown.
- Require fresh, distinct HFP samples for target-audio confirmation and preserve pending sampling deadlines across callback bursts.
- Recheck initial disconnected projection within the existing pre-action evidence window; retain suspension after a submitted request.
- Separate current audio evidence from historical confirmation, classify late watchdog observations as incomplete, and allow teardown grace before marking a corroborated takeover unstable.
- Align log analysis and last-call labels with explicit incomplete observation and corroborated takeover results.
- Expose selector recovery configuration and current eligibility in diagnostics. The one-target-request budget and Samsung Phone ownership remain unchanged.


## Unreleased

- Record projection provider values/status, query generations, triggers and observation age alongside routing evidence.
- Add audio/HFP query quality and per-operation timing, safety/evaluation timing, and timer dispatch delay distinct from logical deadline overrun.
- Add an observational wrong-audio marker and incident records; analyzer reports user failures, unfinished operations and accepted requests lacking HFP confirmation.
- Add optional bounded, private system-service snapshots and a research-to-evidence map. Raw system captures are separate from the redacted app export.

## 0.3.0-beta.11

- Replace the callback-fighting startup guard with a bounded transaction: wait 500 ms after ACTIVE,
  make at most one BMW endpoint request, verify stable target HFP/SCO audio, and stop.
- Treat Telecom's displayed endpoint as diagnostic evidence rather than physical audio proof. A
  split state where Telecom shows BMW while Android Auto owns SCO now forces the one request.
- Make API 37 endpoint-request callbacks observational. Samsung call-start callbacks no longer
  masquerade as user overrides or suspend the transaction, and the app never reasserts after its
  one BMW request.
- Add deterministic regressions for the captured Samsung split-brain trace, transient SCO,
  selector preservation, post-completion manual changes, request-result races, and the strict
  one-BMW-request budget.
- Restore Samsung's manual in-call BMW choice after an unsuccessful split-brain transaction. When
  Telecom still displays BMW but the configured Android Auto endpoint verifiably owns SCO, make
  one bounded request to display Android Auto again so BMW can be selected manually; never retry
  BMW, guess an endpoint, or loop.
- Put privacy-safe environment and evidence snapshots in each structured session trace, including
  projection, call eligibility, Telecom route, endpoint-resolution basis, actual HFP/SCO owner,
  policy decision, request generation/revision, and selector-recovery result.
- Add analyzer diagnostic-completeness checks, an exact selector-recovery parked-test scenario,
  all-HFP-owner service regressions, JDK 17/21 host-contract CI, merge-queue checks, and PR-retarget
  triggers so moving a pull request onto `main` cannot silently skip CI.
- Suspend selector recovery when a protected speaker, handset, wired, or other-Bluetooth route is
  observed; add deterministic and randomized invariants for the recovery action, and correct the
  stale pre-one-shot safety description in the README.

## 0.3.0-beta.10

- Distinguish a replay of the exact same Android `Call` object from a genuinely new call in a
  long-lived `InCallService`, allowing strict late-bind recovery on consecutive calls without
  weakening rebind safety.
- Tear down Bluetooth HFP monitoring on confirmed Android Auto disconnection and create a fresh
  observer only after projection reconnects; the parked manual one-shot retains its explicit
  projection bypass.
- Add deterministic regressions for consecutive calls first observed as active and projection-
  scoped HFP resource use.

## 0.3.0-beta.9

- Fix late-bind recovery when Telecom reports the configured car's current Bluetooth endpoint
  before its available-endpoints snapshot; the transient identity gap is no longer mistaken for
  an unconfigured-device override.
- Latch handset, speaker, and wired route edges during late-bind recovery so a later car callback
  in the same main-loop batch cannot erase a protected user/system choice.
- Restrict settings-change cancellation to routing configuration keys. Persisting last-session
  diagnostics no longer starts an orphan routing trace after the real session has finished.
- Make the framework-double HFP boundary accurately remain unknown until monitoring starts, and
  verify that monitoring starts only for verified Android Auto projection or explicit Route now.
- Add 1,312 adversarial late-bind interleavings covering all evidence orders, protected-route
  insertion boundaries, transient unknown evidence, duplicate callbacks, and seeded callback
  storms, in addition to the existing policy and service suites.

## 0.3.0-beta.8

- Recover automatic routing when Samsung first binds the non-UI Telecom service to an already
  active call, but only from a verified Android Auto/configured-car route with all safety,
  authorization, exact BMW HFP, and endpoint evidence present.
- Continue suppressing late-bind takeover from handset, speaker, wired, unknown, or unconfigured
  Bluetooth routes so process recovery cannot override an explicit user choice.
- Start Bluetooth HFP observation only after Android Auto projection is verified, or after the
  user explicitly presses the manual one-shot button; no permanent foreground service is added.

## 0.3.0-beta.7

- Add privacy-safe process, UI, Telecom-service, and call lifecycle correlation so an exported log
  proves whether Samsung bound the service before or only after the app was opened.
- Record process age, foreground importance, screen-interactive state, battery-optimization
  exemption, app-standby bucket, and the prior process-exit reason at the relevant lifecycle edges.
- Preserve the existing fail-safe for already-active calls; this diagnostic release does not
  guess that a late bind is a fresh call or silently override a route chosen mid-call.

## 0.3.0-beta.6

- Correlate API 37 endpoint-request callbacks with generation-bound, expiring request tickets so
  delayed callbacks cannot leak across call sessions.
- Classify Samsung call-start callback replays separately from app requests and genuine external
  requests, including the pre-ACTIVE/replayed-after-submit ordering observed on the real device.
- Add request IDs, generations, callback latency, endpoint revision, route context, and a compact
  per-session diagnostic summary while retaining salted device aliases and no call identifiers.
- Detect endpoint oscillation and external-request interference as `UNSTABLE` qualification
  results instead of allowing a later target confirmation to produce a false pass.
- Suppress repeated identical suspension events and retain three bounded diagnostic log archives
  so exported evidence is both less noisy and more useful across consecutive calls.

## 0.3.0-beta.5

- Fix automatic routing on Android 17/Samsung when Telecom replays its call-start endpoint request
  around the transition to an active call.
- Preserve the safety behavior that suspends routing for genuine external endpoint requests after
  Car Call Router has acted.
- Add a deterministic production-service regression for the exact callback ordering observed in
  the field and publish the fix as a direct-download debug beta.

## 0.3.0-beta.4

- Align the installed app with the Car Call Router product identity, add Android application
  metadata and an adaptive themed icon, expose releases/source/support inside the app, and add
  reusable Android store-listing metadata with automated drift checks.
- Make GitHub Releases the prominent version and download hub, with latest-version and download
  badges plus direct APK, checksum, verification-report, and full-history links.
- Publish a durable GitHub debug pre-release with a direct APK download, checksum, verification
  report, provenance attestation, and clear signing/qualification limitations.
- Improve repository discoverability with problem-oriented Android Auto, Bluetooth HFP, call-audio,
  and microphone-routing language plus a shareable social-preview asset.
- Add problem-first onboarding, compatibility and FAQ documentation, structured community-health
  files, duplicate-run cancellation, dependency review, and an automated version-consistency gate.
- Pin signed releases to an expected certificate SHA-256, fail closed on signing-key drift, and
  leave only the intentionally deferred target-SDK warning in the Android lint baseline.
- Record the exact installed APK hash and controlled qualification tags for parked device runs, and
  add an offline batch report that enforces build consistency and production-matrix counts.
- Resolve nine Android lint findings: avoid retaining the projection monitor through an async
  query, use plural resources for setup progress, remove redundant drawing, and delete stale
  compatibility strings.
- Centralize plugin and library versions in a Gradle version catalog.
- Enforce ktlint across all Kotlin modules, Kotlin compiler warnings-as-errors, and Android lint
  warnings-as-errors with a checked-in baseline; reformat the existing Kotlin source tree.
- Move the framework-double service harness to a conventional Gradle source layout.
- Extract Android-independent routing, safety, endpoint identity, and trace behavior into a
  dedicated pure-JVM `:core` module shared by the app and service harness.
- Replace the ad-hoc core `kotlinc` launchers with Gradle/JUnit tests and an enforced 90% branch-
  coverage gate.
- Add explicit slow-SCO, head-unit-reclaim, intermediate-handset, projection-unknown, connection-
  order scenarios, plus JSONL regression-trace replay.

## 0.3.0-beta.3

- Add a privacy-safe parked-device capture harness that records only new structured routing traces,
  minimal device/app metadata, and explicit speaker, microphone, and Android Auto observations.
- Add a dependency-free trace analyzer with JSON/CSV output, integrity checks, strict dual-
  confirmation success semantics, and deterministic CI tests.
- Add CI and installer enforcement for APK signature, package/version identity, SDK bounds,
  required permissions, debuggability, and SHA-256 evidence.
- Add service regressions for mid-call authorization/runtime-permission revocation and safe process
  recreation during an already-active call.
- Enforce a single in-flight Telecom request with generation-tokened callbacks and a 2.5-second
  retry gap that respects AOSP's two-second request timeout.
- Add error-specific bounded recovery: timeout may retry, endpoint disappearance requires a fresh
  endpoint snapshot, and external cancellation or unknown failure stops the session.
- Separate the evidence-gathering and routing-action deadlines, and debounce transient alternative
  routes only during the first second after the app's own request.
- Persist a privacy-safe last-call result in the UI and suppress duplicate HFP/projection logs.
- Add a protected signed-beta workflow with APK identity verification, SHA-256 publication, and
  GitHub artifact attestation; add Dependabot, Gradle caching, and CodeQL workflows.

## 0.3.0-beta.2

- Retain stable API 36 builds while enforcing the exact API 37
  `onCallEndpointRequested()` virtual signature in the service harness.
- Preserve self-request identity across either API 37 callback order so an app request is not
  mistaken for an external/user override after the endpoint changes.
- Add stable routing reason codes and versioned, redacted, session-sequenced trace events.
- Record Telecom endpoint confirmation separately from exact target HFP audio/SCO confirmation.
- Add API 37 ordering, external-request, and dual-confirmation regression coverage.

## 0.3.0-beta.1

- Preserve the verified API 34+ Telecom endpoint routing proof of concept.
- Distinguish temporary unknown projection/HFP evidence from confirmed disconnection.
- Treat transient Telecom endpoint-list gaps as a safe pause instead of a permanent false failure.
- Prevent stale Bluetooth endpoints from being misclassified as a user-selected alternative route.
- Add regression coverage for observer uncertainty, endpoint churn, and recovery.
- Run the production-service callback harness as part of Gradle CI.
- Record the owner-confirmed real-device POC and define explicit production-release gates.
