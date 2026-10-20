# Direct-download debug beta

This pre-release makes the current Android Auto Call Switcher beta installable as a direct APK download.

## Install

1. Download the `.apk` attached to this release.
2. Open the APK on an Android 14+ phone and allow the browser or file manager to install unknown
   apps when prompted.
3. Open Android Auto Call Switcher and complete the guided permissions, target-device selection, and
   one-time ADB authorization.
4. Test the manual one-shot route while safely parked before enabling automatic routing.

## Important beta limitations

- This is a debug-signed testing build, not a production-ready release.
- Its signing certificate is pinned and recorded in **apk-verification.txt**. Builds published after
  the protected debug signing setup use the same key and can update each other in place. Older
  beta.11 and beta.12 builds used temporary runner keys: the first protected-key build cannot
  replace them. Uninstall the older app once, then reinstall, reconfigure, and repeat the one-time
  authorization. The separately protected non-debug release certificate also differs.
- Real-car stability qualification is still incomplete. Never use emergency calls for testing and
  do not interact with the app while driving.

The attached `.sha256` file verifies the APK bytes. The GitHub artifact attestation links the APK
to the workflow and source commit that produced it.

## What changed in beta.21

- Addresses accepted target requests that do nothing because Telecom already displays that endpoint while another device owns HFP audio. After a persistent mismatch, reconcile Telecom with the sole stable observed owner, wait for endpoint confirmation, then request the target once more.
- Caps a transaction at two target requests and one reconciliation. Unknown identity, stale/ambiguous audio, changed ownership, external requests and lost eligibility block recovery; no speaker bounce or repeating loop.
- Recovery works without a configured competitor only when live identity resolves the sole observed owner uniquely. A configured competitor further restricts that owner.
- Adds a prominent current call-audio indicator; preferred-device configuration and request acceptance never become audio confirmation. A user report overrides positive platform indicators.
- Preserves known routing failures across disconnect, incomplete observation and service teardown, including offline analysis.
- Adds independent Telecom/HFP transport simulations across callback order, transition delays, request echoes, competitor settings and persistent failure. See [research and verification](https://github.com/itaymatza/android-auto-call-switcher/blob/main/docs/ROUTING-RELIABILITY-2026-10-20.md).

These changes are software-verified. Samsung/Android Auto physical speaker and microphone behavior still requires a new parked trial on this APK.

## Earlier beta.20 behavior

- Fixes duplicate trace fields that caused strict analysis to discard call-boundary events; the analyzer also recovers identical duplicates from older exports.
- Adds `AUDIO_ROUTE_HEALTH_CHANGED` for target signal agreement, Telecom/HFP disagreement, missing SCO, ambiguous owners and other-device audio. These are sampled platform observations, not physical speaker or microphone proof.
- Records the first mismatch after answer, distinct mismatch samples, state spans between sampled transitions, and evidence freshness changes. Unknown intervals remain visible separately.
- Captures pre-request Bluetooth ownership and acceptance state; keeps unknown final SCO/connection evidence nullable.
- Stops interpreting a temporary unknown sample as confirmed audio loss, including analysis of older exports.

## Earlier beta.19 behavior

- Collects pre-answer evidence without issuing a route request; the single automatic request starts only after ACTIVE and the startup quiet window. This avoids the pre-answer request collision observed in beta.18.
- Logs external-request protection decisions, with requester and user intent explicitly unknown. Protected handset, speaker, wired and third-device choices remain respected.
- Samples active-call HFP evidence every 500 ms, below the independent 750 ms freshness limits, and keeps the read-only timer alive across temporary authorization uncertainty.
- Marks whole-call observation incomplete for long gaps or unresolved final evidence; a brief unknown sample that recovers does not permanently contaminate the result.
- Retains up to approximately 24 MB of chronological diagnostics, exports rotation/write-failure limits, and coalesces log UI notifications.
- Adds service regressions for repeated startup requests with and without a configured competitor, long dialing, pre-answer protected choices, absent SCO, short/long observation gaps and pending authorization.
- Telecom acceptance still does not prove microphone/speaker routing. Samsung and car qualification remain required.

## Earlier beta.18 behavior

- Clear endpoint objects between calls and invalidate old request results when an explicit manual transaction starts.
- Preserve unfinished dialing requests through temporary evidence loss at answer; block overlapping Route now attempts.
- Protect resolved third-device Bluetooth changes and API 37 handset/speaker/wired/third-device requests.
- Treat multiple simultaneous reported HFP owners as ambiguous, never as verified target audio or safe selector recovery.
- Move process, power and previous-exit diagnostics off application/call-start callbacks; cache only bounded-age observations.
- Read paired-device names off the setup UI thread with a bounded timeout; discard results after leaving the screen.
- Persist incomplete observation when Telecom unbinds mid-call and reject parked reports spanning different calls.
- Stage releases as drafts, verify downloaded APK/digest/report consistency before publication, and preserve published assets on retry.
- Validate signed release tags against source versions, exclude commit-specific debug tags and keep beta releases prereleases.

## Earlier beta.17 behavior

- Separates exact classic HFP connection/SCO evidence, device-name/alias identity and Bluetooth
  power diagnostics into independent bounded zero-queue workers. Slow optional reads cannot
  starve SCO verification; power reads cannot starve identity inspection.
- Bluetooth connection/audio/name/alias/power broadcasts invalidate cached and in-flight evidence.
  Superseded queries drain before a fresh read; no work queues grow behind stalled Binder calls.
- Multiple-device endpoint matching requires fresh, complete live names and aliases. Saved names
  cannot authorize a route. Unknown identity is not treated as a proven competing route. The
  single-device/single-endpoint topology fallback remains available.
- Clears all HFP evidence on close, releases replaced proxies and prevents late publication/logging.
- Adds 19 production HFP tests, strict identity tests and three service regression scenarios.

## Earlier beta.16 behavior

- Gives the audio-mode probe and optional communication-device/inventory/microphone-mute
  diagnostics separate bounded, zero-queue workers. A blocked or failed diagnostic read
  cannot suppress fresh audio-mode evidence; fresh diagnostics cannot rescue stale mode evidence.
- Exports independent diagnostic freshness and age in the call trace. Inventories still do
  not prove physical microphone or speaker routing.
- Clears cached evidence on shutdown and drops late completion/ inventory logging.
- Adds production audio-probe regression tests for worker saturation, stalls, errors,
  stale/late results, lifecycle cleanup and privacy-preserving diagnostic exports.

## Earlier beta.15 behavior

- Moves call eligibility and protected authorization reads off the service and UI threads.
  Pending, stale, superseded and failed reads cannot authorize a routing request.
- Moves projection provider reads, cursor access and cleanup to a bounded worker; broadcasts
  invalidate in-flight results and delayed positive results stay unknown.
- Compiles the real Android 17 endpoint-request override with the supported API 37 toolchain,
  preserving Android 14+ installation, target API 36 and the existing package/signing identity.
- Adds production-monitor lifecycle tests and policy/service tests for pending evidence,
  deadlines, permission loss and call identity changes.

## Earlier beta.14 behavior

- Starts one bounded preferred-device routing transaction during outgoing dialing, then rechecks fresh
  audio evidence at answer without replacing a pending request or fighting manual selections.
- Samples HFP ownership throughout the call, including after a failed startup request.
- Records observed call duration and call/dialing/answer-relative event timing, late service
  binding, observation gaps and audio loss. Request acceptance alone does not count as success.
- Maps research questions to exported evidence, adds Bluetooth power and microphone-mute
  diagnostics, and labels unavailable settings and unknown external request origins.
- Adds deterministic tests for answer takeover, delayed callbacks, absent target audio,
  manual overrides and incomplete observation. Physical microphone/speaker behavior and
  seamless Samsung/Android Auto handover still require controlled real-device testing.
