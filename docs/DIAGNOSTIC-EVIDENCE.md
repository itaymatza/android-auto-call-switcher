# Diagnostic evidence and research reconciliation

This maps the September 26 **Android Call Routing Analysis** report and the
October 8 review of 4,059 exported log lines to observable evidence. A report's
proposed mechanism is a hypothesis until reproduced on the phone. Telecom request
acceptance, displayed endpoint, exact-device HFP/SCO observation, and physical
speaker/microphone behavior remain separate facts.

## What is captured

| Research question | Evidence | Scope and limitation |
| --- | --- | --- |
| Did the request complete, or did audio actually follow? | Session/request IDs, endpoint revision, request result, endpoint callbacks, exact-device HFP/SCO samples | Already implemented; none proves audible microphone/speaker output |
| Did diagnostics or safety checks stall the main callback path? | `OPERATION_STARTED`/`OPERATION_FINISHED` for authorization, runtime permissions and call safety; `EVALUATION_TIMING` duration/queue delay | Added here; an unfinished operation identifies the last entered operation, not a proven root cause |
| Was a timer late because of sleep, dispatch, or an already-expired deadline? | Requested delay, elapsed/uptime deltas, `sleep_delta_ms`, `dispatch_late_ms`, and logical `late_ms` | Separate measurements; a late deadline alone does not prove a blocked Handler |
| Was Android Auto really observed, or merely inferred? | Provider raw value, status, generation, trigger, pending flag, sample age and query duration | Added here; no Bluetooth/Wi-Fi heuristic authorizes routing; querying does not erase the last observation's age |
| Was AudioManager evidence unavailable, stale, or failed? | Mode/device type, sample age, query duration, quality and failed operation; separate getter entry/exit logs | Added here; public device type cannot identify BMW versus another SCO device or prove HAL audio |
| Did the Bluetooth query stall or return ambiguous negative evidence? | Sample quality, proxy availability, trigger, total query duration and per-getter timing with redacted device IDs | Added here; public getters may return false/empty on service failure without throwing, so negative evidence remains ambiguous |
| Why was selector recovery absent? | Configured/resolved competitor, HFP owner, policy reason, recovery context and result | Already implemented; preserve external-control suppression and exact configured identity |
| Did success at call start last? | Post-confirmation samples, event-driven changes, fresh HFP sample contract, observation-gap/teardown classification | Already implemented; sparse observations are not continuous physical validation |
| Did the user hear wrong audio despite apparently correct framework state? | **Mark wrong call audio** records `USER_AUDIO_REPORT` during a session or a recent-call marker between calls | Added here; never starts a new routing transaction; a session report prevents the analyzer calling it `PASS` |
| Where should investigation focus? | Change-triggered `DIAGNOSTIC_INCIDENT`, operation maxima, unfinished operations, unknown audio samples, accepted-without-HFP flag | Added here; incident records stay in the bounded existing rotated log, not an unlimited capture |

Logs are app-private, rotated, and exported using the existing redaction contract.
No numbers, handles, raw addresses, device labels, audio recordings, or source
package identities are added. The analyzer retains compatibility with older traces;
unknown new metrics on old exports are not evidence of zero latency or perfect
observation. New numeric maxima default to zero when absent, so inspect the raw
events before comparing builds.

The marked incident and the surrounding ordinary trace are exported once. Do not
duplicate trace records into a second section: it would corrupt session sequence
validation. Export promptly after a problem; rotation can eventually remove old
evidence. Automatic pinning of an incident across rotations remains future work.

## Deep system state, when app evidence is insufficient

The normal APK cannot read every system service or another app's logs. For a
controlled, parked investigation with an already-authorized ADB device:

```sh
python3 tools/capture_system_state.py --serial DEVICE --output verification/device-runs/incident-system
```

This supplementary tool reads `telecom`, `audio`, `bluetooth_manager`,
`media.audio_policy`, `media.audio_flinger`, the router's package/AppOp state, and a
small allowlist of device/software properties. Each command has a timeout and a
2 MB retained-output cap. Captures are **sequential snapshots**, not atomic state or
a continuous trace. `capture.json` records timing, denial, unavailable output,
failure, truncation, and timeout. Partial access returns a nonzero exit code but
preserves the evidence that was available. Existing output is never overwritten.

**These files are raw and may contain private system/call information.** They are
created with private directory/file permissions, ignored by Git under
`verification/device-runs/`, and are never appended to the app's redacted export.
Review and redact them before sharing. No root, new permission, Bluetooth routing,
or system setting is changed by this tool. System captures are optional development
diagnostics, not an installation requirement.

For an unresolved scheduling/Binder stall, a carefully scoped bugreport or Perfetto
capture can be the next investigation. For codec/SCO negotiation, a Bluetooth HCI
capture may be useful, but enabling and obtaining it is platform-dependent and
must be a separate explicit diagnostic action. These are not silently enabled or
claimed as implemented in the APK.

## Findings that remain unproven

- AOSP's already-current-endpoint shortcut is a credible explanation for an
  accepted request with no SCO change; Samsung's exact behavior remains unverified.
- The observed other SCO owner is not necessarily Android Auto. Device selection
  and redacted role mapping must establish identity.
- The report's fixed 800–1200 ms settling recommendation and BMW codec timing are
  not measured guarantees. Current bounded evidence-based settling remains intact.
- `AudioManager.getCommunicationDevice()` is corroborating framework evidence,
  not proof of physical PCM delivery or microphone quality.
- API 37 endpoint-request callbacks do not identify who requested the route.
  Correlation classifies observations, not the human or source package.
- Companion-device association did not grant call-routing access on the tested
  setup; the report's authorization guarantee is not accepted.

## Validation and remaining work

Deterministic service tests cover slow authorization, report-without-rerouting,
projection fields, single incident emission and existing callback/lifecycle cases.
Analyzer tests cover pending operations, stale/error evidence, timing attribution,
accepted-without-HFP and user-reported failures. System-capture tests exercise
timeouts, large output, partial permission denial, private files and overwrite
protection. These tests do not simulate Samsung's Bluetooth stack.

Still needed: real-device comparison of successful and failed calls, physical
microphone/speaker observations, longer event-driven calls, and qualification of
manual selection, screen-off/reboot/process death, connection order, and Android
Auto media/navigation continuity. There is no automatic target→competitor→target
experiment in this change. That requires a separate bounded-policy design and
device evidence.

Primary contracts: [InCallService](https://developer.android.com/reference/android/telecom/InCallService),
[BluetoothHeadset](https://developer.android.com/reference/android/bluetooth/BluetoothHeadset),
[AudioManager](https://developer.android.com/reference/android/media/AudioManager),
[dumpsys](https://developer.android.com/tools/dumpsys).
