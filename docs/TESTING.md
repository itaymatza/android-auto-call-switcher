# Testing guidance

## Deterministic host-JVM suites

Install a JDK 17 or newer and a Kotlin compiler, then run:

```sh
bash tools/test-all.sh
```

The script runs ktlint for every Kotlin module, the pure-JVM `:core` JUnit suite with its enforced
95% line / 90% branch-coverage gates, the production-service JUnit harness with enforced 90% line
and 75% branch floors, and the Python trace/APK-tooling tests. The service coverage scope contains only
`RouterInCallService`, `AddressedTelecomRouter`, `RouterSettings`, and `SessionBridge`; framework
stubs and test-driver code are excluded. The core suite includes named policy rules, 5,000 seeded
traces (250,000 transitions), another 750,000 seeded callback/evidence transitions, exhaustive
automatic/manual eligibility combinations, and split-brain, timing, transient-SCO, and manual-
control scenarios. It writes
fresh local logs under `verification/current/`, which is intentionally ignored by Git. The
framework-double service runner remains available for focused debugging, but normal verification
runs it through Gradle/JUnit so JaCoCo can measure the production boundary.

The production-service harness includes the captured Samsung failure shape: Telecom displays the preferred device,
Android Auto owns SCO, and another `InCallService` request arrives immediately after ACTIVE. It
also verifies the settling delay, silent HFP ownership changes sampled during settling, strict
one-target-request budget, stable-SCO requirement, result races,
manual selector preservation, lifecycle cancellation, late binding, and request-marker isolation.
The [call-audio mechanism and CI matrix](CALL-AUDIO-MECHANISM.md) also cover Android 17's conditional
Audio Managed SCO path, read-only audio-framework diagnostics, endpoint-list churn after a request,
late voice-stream activation and a bounded HFP query budget.

These checks exercise decision rules and lifecycle behavior. They do not install an APK, emulate Android Telecom, validate protected-permission admission, or test a real Bluetooth stack, microphone, projection host, headset, or vehicle.

## Android build and static checks

With a suitable Android SDK:

```sh
bash gradlew :core:check :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --stacktrace --console=plain
```

The repository workflow runs that command on pushes, merge-queue candidates, and pull requests with
read-only workflow permissions and uploads only the generated debug APK as an expiring artifact.
PR `edited` events are included so retargeting a branch onto `main` cannot bypass required checks.
The policy/service/trace host contracts run independently on JDK 17 and 21 for faster failures and
runtime-compatibility coverage.

Kotlin formatting is enforced with ktlint's official style and a 140-character maximum. Run
`bash gradlew :app:ktlintFormat :core:ktlintFormat :verification:service-tests:ktlintFormat` before
committing broad mechanical changes. Kotlin compiler warnings and Android lint warnings are treated
as errors; the deferred target-SDK finding is recorded in `app/lint-baseline.xml`, so new source
findings fail the build. Dependency availability is handled by weekly Dependabot pull requests,
not Android lint. Plugin and library versions are centralized in `gradle/libs.versions.toml`.

The Gradle workflow and both installation scripts run `:verification:service-tests:check`, so
callback-order and lifecycle regressions plus their coverage floors are exercised against the same
compiled `:core` module. CI publishes the JaCoCo HTML/XML and JUnit XML as a retained artifact and
adds the exact module totals to the run summary.
CI also runs the standard-library-only device trace analyzer and
APK-verifier tests. After building, CI verifies the generated APK's signature,
package/version identity, SDK bounds, required permissions, and debuggable state, then publishes
the verification record beside the APK.

## Verify device authorization

When more than one ADB target is listed, address the physical phone explicitly:

```sh
adb devices
adb -s PHONE_SERIAL shell cmd appops get --uid org.carcallrouter.companion MANAGE_ONGOING_CALLS
```

The required result includes `MANAGE_ONGOING_CALLS: allow`. Refresh the app's Diagnostics and confirm the current status reports runtime permissions and Telecom authorization as true. Historical `AUTH_MISSING` events may remain after a successful grant and do not override the current status.

`Last Telecom binding: Never observed` is normal before the first eligible call. It becomes a useful failure signal only if it remains unchanged during an authorized normal test call.

## Capture one parked device run

From macOS or Linux with `adb` and Python 3 available, connect the authorized phone and run:

```sh
bash tools/capture_device_run.sh --serial PHONE_SERIAL --scenario outgoing \
  --tag cold-start --tag battery-unrestricted --tag android-auto-first
```

Supported scenarios are `incoming`, `outgoing`, `selector-recovery`, `override`, `hold-resume`, `reconnect`,
`second-call`, `conference`, and `other`. Override and reconnect runs require exactly one matching
route/disconnect tag so the corresponding physical observation cannot be skipped. Omit `--serial`
only when exactly one authorized ADB device is connected. If the build
uses a custom application ID, also pass `--package YOUR_APPLICATION_ID`. Repeat `--tag` to record
the lifecycle, battery, connection-order, disconnect, override, or multi-call matrix cells exercised
by that run; `--help` lists the controlled tag names.

The script verifies that the package is installed and `MANAGE_ONGOING_CALLS` is allowed. It takes
a baseline, prompts for one parked non-emergency call, then captures only new, structured
`ROUTING_TRACE` records. Each new session must contain its environment plus at least one complete
evidence snapshot for a qualification verdict. It never starts a call, clears logcat, runs broad `dumpsys` collection, or
stores the ADB serial, phone number, Bluetooth name, or raw Bluetooth address.

Each local, Git-ignored directory under `verification/device-runs/` contains:

| File | Contents |
|---|---|
| `device.txt` | Scenario/tags, UTC time, phone/build/One UI identity, app version, installed APK SHA-256, and authorization state. |
| `trace.log` | Only new schema-1 structured routing records; prior sessions are removed. |
| `app-events.log` | New redacted app events, including HFP query timing when private app logs are available. |
| `report.txt` / `report.json` | Per-session status, confirmations, latencies, finish reason, and anomalies. |
| `observations.txt` | Parked human checks for native HFP speaker/microphone and preserved Android Auto behavior. |
| `verdict.txt` | `PASS` only when every new session has complete trace evidence and every required observation is `yes`. |

Review all artifacts before sharing them. They are designed to be privacy-safe, but device model
and timing can still be identifying context. To analyze a previously exported app log without ADB:

```sh
python3 tools/analyze_device_trace.py exported-log.txt
python3 tools/analyze_device_trace.py exported-log.txt --format json
```

The in-app log export also contains `HFP_QUERY_TIMING` records. Keep the full export when a
deadline fires late: the structured trace records `TIMER_SCHEDULED`, `TIMER_CANCELLED`,
and `TIMER_FIRED` with elapsed time, uptime, and calculated sleep delta. The capture harness keeps the structured `trace.log` and a separate `app-events.log` for
Bluetooth proxy query latency when private app logs are available. The logcat fallback keeps
only structured trace records. A late timer is evidence of delayed evaluation, not by itself proof of why it
was delayed. Correlate the exported timing records before changing the policy or adding retries.

The analyzer reports malformed records, unsupported schemas, sequence gaps, time regressions,
missing starts, and duplicate finishes as `INVALID`. A session that confirms only the Telecom endpoint is `INCOMPLETE` unless the policy explicitly
failed, in which case it is `FAIL`. A policy failure remains `FAIL` even if target HFP audio was confirmed earlier in the call.
A session is `UNSTABLE` when exact target audio is eventually confirmed
but the trace also shows an endpoint oscillation such as the preferred device → handset → the preferred device or a later
loss of confirmed the preferred device HFP audio (including the post-confirmation watch verdict). Endpoint-request
callbacks are reported for diagnostics only because Android can emit them during call startup without
a user action. A session cannot be `PASS` without a finish, exact target HFP audio confirmation, and
no detected instability. `selector_recoveries` reports the bounded Samsung selector-restoration path;
it remains a routing failure until a later manual selection of the preferred device produces exact target HFP audio.
The report separately marks `diagnostic_complete`, lists any missing request contexts, identifies a
Telecom-target/other-HFP mismatch without assuming which device owns that HFP audio, and reports the final HFP owner and selector-recovery
confirmation. Use `--require-diagnostics` when checking a capture outside the harness.
The app's last-call result labels HFP confirmation separately from later instability. It is not
proof of the physical speaker or microphone: those require the parked-car observation.

For the reported Samsung selector regression, run `--scenario selector-recovery`. In addition to
the standard speaker, microphone, and Android Auto observations, the harness records whether the
Phone UI showed the preferred device while Android Auto actually owned audio, whether recovery made the UI truthful,
whether the preferred device became selectable, and whether the final manual selection moved both speaker and mic.

After collecting multiple runs, summarize the entire batch with:

```sh
python3 tools/summarize_qualification.py verification/device-runs
python3 tools/summarize_qualification.py verification/device-runs --format json
python3 tools/summarize_qualification.py verification/device-runs --require-ready
```

The summary counts only combined `PASS` runs toward the matrix, verifies that the phone build and
exact installed APK hash stayed unchanged, lists missing cells, and calculates the approximate
`3/n` upper failure-rate bound only when the batch has no failures. `--require-ready` exits nonzero
until all required counts pass, without uploading any device evidence.

The batch summary also rechecks raw `app-events.log` parked reports. A failed, unchecked, or
malformed report vetoes that run even if `verdict.txt` says `PASS` or `parked-reports.json` is stale.
Captures written with the parked-report gate must include their raw app-event file. Legacy
captures without that gate remain readable; a positive in-app report never upgrades a failed run.

## Current device evidence

On 2026-09-19, the project owner confirmed the proof of concept on the intended Samsung phone, aftermarket Android Auto unit, and native 2018 BMW X1 Bluetooth system. The app successfully moved active call speaker and microphone routing to the BMW endpoint while Android Auto remained active. Intermittent behavior was also reported, so this is evidence for technical feasibility—not yet a production stability claim.

## Exact parked-car test

1. Park safely, switch off the engine if local conditions require it, and do not begin driving during the test.
2. Confirm the phone is connected simultaneously to the projection unit and the intended native Bluetooth hands-free device.
3. Confirm Android Auto navigation/media still works. Select the native hands-free device in the app and leave automatic mode off.
4. Confirm the app reports runtime permissions granted and protected Telecom authorization detected.
5. Place an ordinary non-emergency call to a consenting helper or voicemail. Never test with an emergency number.
6. After the call becomes active, tap **Route this call now** once.
7. Confirm the app reports the target endpoint as verified. Speak and listen through the intended device; ask the helper which microphone is heard.
8. Confirm navigation/media remains active on Android Auto.
9. Manually select speaker and then the preferred device in the Phone UI. Confirm every endpoint remains selectable and the app never switches back after its single transaction.
10. End the call and complete the capture prompts. If working manually, export the redacted diagnostic log after a failure and inspect it before sharing. Only after the one-shot test succeeds should automatic mode be enabled.

Repeat separately for outgoing and incoming calls. Then test call hold/resume and a second incoming call; the expected safe behavior is to stop automatic reassertion. Conferences and emergency calls must never be used as positive routing tests.

For a successful run, the exported trace should contain both
`TELECOM_ENDPOINT_CONFIRMED` and `TARGET_HFP_AUDIO_CONFIRMED` for the same `session`. The events
include a schema version, sequence, and elapsed milliseconds. Endpoint confirmation without HFP
audio confirmation is incomplete evidence and must not be counted as a successful microphone and
speaker result. Endpoint-request records classify the request as `SELF` or `EXTERNAL` for
diagnostics only and include generation correlation, callback age, route-match flags, policy phase,
and request count. The terminal record summarizes requests, route changes,
oscillations, callback classes, final route, HFP state, and SCO state.

## Production stability matrix

Do not promote a beta to a production release until one unchanged APK passes all of the following on the intended phone/car setup:

| Scenario | Minimum evidence | Expected result |
|---|---:|---|
| Incoming and outgoing calls | 20 of each | Preferred-device speaker and microphone selected; Android Auto navigation/media remains available. |
| Cold start, warm start, screen off, and post-reboot | 5 calls per state | Same routing result without opening the app. |
| Samsung unrestricted, optimized, and restricted battery states | 5 calls per state | Binding behavior is measured explicitly; unsupported restricted behavior is documented rather than guessed. |
| Android Auto first vs preferred Bluetooth first | 10 calls per connection order | Callback ordering does not change the result. |
| Consecutive calls and calls after idle | 10 calls | No stale session, request budget, or endpoint identity leaks into the next call. |
| Temporary projection/HFP unknown callbacks | Instrumented logs for each occurrence | Requests freeze and recover inside the bounded window; no false user-override classification. |
| Confirmed preferred-device or projection disconnect | 5 controlled trials each | Guard stops and does not resume automatically in that call. |
| Speaker, handset, wired, or other Bluetooth override | 3 trials per route | User choice is respected immediately and is not fought. |
| Hold/resume, second call, and conference | 3 trials each | Automatic reassertion stops for the session. |

Acceptance requires no unexplained routing failure, no route fight after a user override, no request outside the eligibility policy, and no loss of Android Auto media/navigation. Use a fresh capture directory for every row, retain failed runs, and keep the APK version unchanged throughout a qualification batch. Never publish raw `dumpsys` or Bluetooth data without reviewing it for personal information.

Record the full Android build fingerprint for every qualification batch and repeat the baseline
after an OS or major One UI update. Freeze features during a batch; every failure must first become
a deterministic regression fixture. With zero failures in `n` independent trials, the approximate
one-sided 95% upper bound on the failure rate is `3/n`: 60 clean trials support a bound below 5%,
and 100 clean trials support a bound below 3%.

## In-app parked observations

Use **Test safely → Record parked call test** after a parked normal cellular call. Report each
of speaker output, microphone input, Android Auto navigation, and media resumption as checked
and working, checked and failed, or not checked. Confirm microphone input with the remote
party while separating the phone and target microphone; merely seeing a selected route is not
a microphone test. Media may pause normally during a call; test resumption after it ends.
Export the redacted log. `USER_PARKED_TEST` is operator evidence, separate from automatic HFP
confirmation, and does not automatically pass the capture/qualification tools. Complete the optional in-app
report before ending capture. `parked-reports.json` retains the separate report summary; any
failed, unchecked, or malformed new report vetoes a passing run even if command-line
observations say yes. Existing captures without in-app reports retain their operator gates.

The foreground compatibility check reports connection observations only. Verify classic-only,
dual-transport, LE-only and hearing-aid devices, Bluetooth off, permission revocation, return
from device settings, and stopping/restarting the activity on physical hardware. No HFP
connection means the routing service still waits/stops under its existing bounded policy.
