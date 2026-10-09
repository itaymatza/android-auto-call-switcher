# Production readiness

## Current classification

`0.3.0-beta.16` is a production-hardening beta. The real-device proof of concept validates the core Telecom endpoint approach, but intermittent behavior means the project must not yet be described as production-ready.

## Completed engineering gates

- Supported API 34+ `CallEndpoint` routing; no deprecated Bluetooth-audio request path.
- Samsung Phone remains the default dialer; protected access is verified through `TelecomManager`.
- Exact target resolution fails closed on ambiguity.
- Bounded, callback-driven requests with endpoint-change verification.
- User alternatives, emergency/unclassified calls, multiple calls, hold, conferences, authorization loss, confirmed observer loss, and lifecycle teardown stop automation.
- Temporary unknown projection/HFP evidence and transient endpoint-list gaps pause safely and can recover without a false disconnect or false user override.
- Deterministic policy, randomized transition, and production-service callback suites run locally; the service suite is also wired into Gradle CI.
- CI enforces independent coverage floors: at least 95% line and 90% branch coverage for the pure
  routing core, plus at least 90% line and 75% branch coverage for the production Telecom-service boundary.
  Test-driver and Android-framework stub classes are excluded from the service measurement.
- Diagnostics redact phone numbers, device names, and raw Bluetooth addresses.
- The API 37 endpoint-request signature and both callback orders are regression-tested; final
  dispatch validation remains a real Android 17 device gate; builds now compile the actual API 37 override.
- Audio-mode evidence uses an independent bounded worker; slow optional communication-device,
  inventory or microphone-mute queries cannot starve it. Both streams expire independently after
  750 ms; diagnostic quality/age are exported and closed probes retain no usable evidence.
- Structured evidence separates Telecom endpoint confirmation from exact target HFP audio/SCO confirmation.
- A privacy-bounded ADB harness isolates each new device session, validates trace integrity, records
  physical speaker/microphone and Android Auto observations, and emits a conservative combined
  pass/fail verdict. Its parser has deterministic CI coverage.
- Qualification aggregation verifies one unchanged installed APK and phone build, counts only
  passing evidence toward every matrix cell, identifies missing trials, and reports the `3/n`
  bound only for failure-free batches.
- CI fails on APK signature rejection, package/version or SDK drift, missing required permissions,
  and unexpected debuggability; its artifact includes the exact APK and certificate SHA-256 values.
- The main-branch debug pre-release is a downstream job of both JDK host-contract runs and the
  Android build/lint/APK gates. A failed gate skips publication; only the downstream job can access
  protected debug signing credentials, and reruns fill in missing assets of an interrupted release.
- Service regressions cover authorization and runtime-permission revocation during a pending route,
  plus process recreation without taking over an already-active call.
- Late-bind recovery is tested across every ordering of projection, HFP, endpoint-snapshot, and
  current-route evidence plus 1,000 seeded callback storms. Protected handset/speaker/wired edges
  are latched even when callbacks coalesce, while a pre-snapshot car Bluetooth route remains
  unresolved instead of being misclassified as a user override.
- Requests are generation-tokened. The target request is submitted once. A timeout can be followed
  by late HFP audio confirmation within the bounded observation window, but never triggers another
  target request. Selector recovery is a separate one-shot action under the exact split-state guard.
- API 37 request markers are call-generation-bound and expire; late result callbacks are ignored.
  Startup-request replays are classified separately, while a later external request suppresses
  selector recovery. The diagnostic analyzer flags route oscillation and post-confirmation audio loss.
- Evidence collection and routing action use separate deadlines, transient alternative routes are
  debounced only during the immediate post-request settling period, and the UI persists the last
  completed session result.
- Call-start settling re-queries HFP at 250 ms intervals so silent ownership changes can extend
  the existing bounded quiet period; it does not add a route request. The last-call result and trace
  distinguish a confirmed HFP route that later became unconfirmed.
- A protected, manually triggered/tag-triggered workflow builds a non-debuggable signed APK,
  verifies its identity, publishes its SHA-256 digest, and creates a GitHub artifact attestation.

## Release blockers

- Complete the real-car stability matrix in [TESTING.md](TESTING.md) using one unchanged APK and
  retain one capture-harness record per trial.
- Classify every observed failure from the redacted event sequence and add a deterministic regression before changing routing behavior.
- Measure the 300–900 ms settling window against new parked-car traces before changing its bounds;
  the report's 800–1200 ms suggestion is a hypothesis until that test isolates request timing.
- Pass the complete Gradle build, JVM tests, service tests, Android lint, and APK verification for
  the release commit; repeat the APK gate against the separately signed, non-debuggable release.
- Verify upgrade and fresh-install flows, including AppOps authorization detection, revocation, reboot, process death, and settings preservation.
- Configure and protect the `beta-signing` environment secrets and pinned certificate variable,
  run the signed workflow, and prove that an update signed with the same key retains application
  data and the AppOps authorization.
- Confirm the privacy and safety documentation matches the final behavior and that no real device identifiers or private logs are committed.

## Stability rule

Do not add retries merely to improve a success percentage. Each phase is one-shot; outgoing calls have a dialing phase and answer verification with at most two automatic target requests for the entire call. Incoming calls have one answer-time target request. The explicitly configured competing endpoint may receive a separate, single selector-recovery request only when it owns HFP audio and Telecom still displays the target after the verification window. Unknown evidence freezes routing; confirmed safety loss stops it; an alternative route that may be a user choice is never fought.
