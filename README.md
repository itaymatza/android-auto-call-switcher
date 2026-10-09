# Android Auto Call Switcher

![Android Auto Call Switcher](docs/assets/social-preview.png)

[![Build](https://github.com/itaymatza/android-auto-call-switcher/actions/workflows/build.yml/badge.svg)](https://github.com/itaymatza/android-auto-call-switcher/actions/workflows/build.yml)
[![CodeQL](https://github.com/itaymatza/android-auto-call-switcher/actions/workflows/codeql.yml/badge.svg)](https://github.com/itaymatza/android-auto-call-switcher/actions/workflows/codeql.yml)
[![Latest beta](https://img.shields.io/github/v/release/itaymatza/android-auto-call-switcher?include_prereleases&label=latest%20beta)](https://github.com/itaymatza/android-auto-call-switcher/releases)
[![Downloads](https://img.shields.io/github/downloads/itaymatza/android-auto-call-switcher/total?label=downloads)](https://github.com/itaymatza/android-auto-call-switcher/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[**Download latest APK release**](https://github.com/itaymatza/android-auto-call-switcher/releases) · [All versions and release notes](https://github.com/itaymatza/android-auto-call-switcher/releases)


**Use your chosen Bluetooth device for call speaker and microphone audio while Android Auto handles everything else.**

The installed app is named **Android Auto Call Switcher**. The canonical source repository is
`itaymatza/android-auto-call-switcher`. GitHub redirects the previous repository links; the
installed package and signing identity stay stable for in-place updates.

## The problem

Android Auto can take over call audio even when you want calls on a separate Bluetooth device.
That preferred device might be earbuds, a headset, a speakerphone, or a car's hands-free system.
The goal is to choose that call device once while keeping Android Auto available for maps, music,
and its other functions.

Android Auto Call Switcher automates the same choice a user can make from the active-call audio selector:
when an eligible cellular call becomes active, it asks Android Telecom to use a **current,
user-chosen Bluetooth call endpoint**. Android Auto remains connected for navigation and media.
The app does not become the default dialer or manipulate general media routing through
`AudioManager`.

### Common symptoms this project targets

- Android Auto uses the wrong or poor-quality microphone for phone calls.
- Calls go through an aftermarket Android Auto head unit instead of the car's native Bluetooth.
- You want Android Auto to stay connected for maps and music while another Bluetooth HFP device
  handles call speaker and microphone audio.
- Manually selecting the preferred Bluetooth call device during every call works, but you want that
  call-audio selection automated.

If those phrases describe your problem, start with [Is this for me?](#is-this-for-me) and the
[compatibility guide](docs/DEVICE-COMPATIBILITY.md).

See the [product contract and implementation review](docs/PRODUCT-GOAL.md) for the exact goal,
implemented safeguards, and remaining transport/setup limitations.

## Is this for me?

It may fit when all of these are true:

- the phone runs Android 14 or newer;
- Android Auto and the preferred Bluetooth hands-free device are connected at the same time;
- manually choosing that Bluetooth device during a call already fixes both speaker and microphone;
- a one-time ADB authorization is acceptable.

It is not a fix for pairing failures, broken Bluetooth hardware, media-routing problems, emergency
calls, or systems where the preferred device is absent from Android's in-call audio selector. See
the [compatibility guide](docs/DEVICE-COMPATIBILITY.md) and [FAQ](docs/FAQ.md) before installing.


> This repository contains source code, deterministic tests, and a build workflow. The tracked source tree does **not** include device configurations, Bluetooth addresses, signing materials, APKs, or exported logs. Successful GitHub Actions runs publish a generated debug APK as a downloadable build artifact.

## Project status

The routing proof of concept has been confirmed by the project owner on the intended Samsung + Android Auto + native BMW Bluetooth setup: an active cellular call moved to the selected native hands-free endpoint, including its microphone, while Android Auto remained active. That confirms the core approach, not production reliability. Version `0.3.0-beta.17` requests the selected call device during outgoing dialing and revalidates at answer, with a maximum of two automatic target requests per call. It samples HFP ownership throughout active calls and records call-relative timing, observation gaps, and user-reported wrong audio. Incoming calls retain answer-time routing. Protected call eligibility, authorization and projection-provider reads now run off-thread, and pending or delayed evidence cannot authorize a request. Audio-mode checks and optional device inventories have separate bounded workers and independent freshness. Exact HFP audio, live identity labels and power diagnostics are also isolated; Bluetooth change events invalidate old reads. Multiple-device endpoint matching uses fresh live labels rather than saved names. Seamless physical audio remains subject to device qualification; see the [production-readiness gates](docs/PRODUCTION-READINESS.md).


## Public-repository posture


The tracked tree contains no real target-device settings. Device names and Bluetooth addresses are selected at runtime and stored only in the application’s private local preferences. Generated APKs, IDE configuration, local SDK configuration, test logs, captures, and conventional key/certificate files are excluded by `.gitignore`. GitHub Actions compiles, unit-tests, lints, and verifies the source. Normal builds upload an expiring debug artifact; after the host contracts and Android build both pass on `main`, the same workflow publishes a commit-specific debug pre-release signed with a protected, persistent key with an APK, digest, and verification report. A failed gate publishes no new APK. No APK is committed to the repository.


A public repository still exposes its files, commit history, issue/discussion content, and workflow logs. Do not commit exported logs, screenshots, pairing records, keystores, certificates, API keys, or private test notes. If the repository should not be forked, copied, or associated with its GitHub owner, use a private repository and consider a separately planned history rewrite.


## Configuration


### Build-time identity


The source namespace is the generic value `org.carcallrouter.companion`. Override the installed application ID and version metadata through Gradle properties without changing source:


```sh
bash gradlew \
  -PAPP_APPLICATION_ID=example.callroute \
  -PAPP_VERSION_CODE=19 \
  -PAPP_VERSION_NAME=0.3.0-beta.17 \
  :app:assembleDebug
```


`APP_APPLICATION_ID` defaults to `org.carcallrouter.companion`; `APP_VERSION_CODE` defaults to `19`; and `APP_VERSION_NAME` defaults to `0.3.0-beta.17`. Choose an application ID that you control before distributing a build. The source namespace remains generic and fixed so Kotlin and manifest class references stay consistent.


### Runtime configuration


The app is configuration-driven at runtime and has no car-brand matching rules. Select a paired classic Bluetooth HFP call device—a headset, earbuds, speakerphone, or car system—regardless of brand. LE Audio-only targets are not supported by the current audio verification backend. This is a capability-based design; only the documented Samsung/BMW setup has owner-confirmed physical test evidence so far. Grant the requested runtime permissions, select the exact paired call device, complete the one-time ADB authorization, and verify the result in the app. If the Android Auto head unit is a separate Bluetooth call device, optionally select that exact competing device. This permits one bounded manual-selector recovery when it is confirmed to own HFP audio after a failed target request; it does not enable another target request. The UI contains no built-in device name, address, car brand, phone brand, or dialer requirement.

## Why ADB is required

`MANAGE_ONGOING_CALLS` is a `signature|appop` permission. Android documents the companion path specifically for a physical **wearable**; associating a car is not a supported general grant mechanism. A normal utility APK also cannot request this permission through a runtime dialog. The other supported route is becoming the default dialer, which requires a complete dial pad plus incoming and ongoing call UI and would replace the user's existing Phone experience. This project deliberately does not impersonate an incomplete dialer.

The selected architecture is therefore a non-UI `InCallService` with a one-time ADB AppOps grant. The app checks `TelecomManager.hasManageOngoingCallsPermission()` and never treats a command, button tap, or service declaration as proof of authorization.


See the exact [authorization and troubleshooting guide](docs/AUTHORIZATION.md), [configuration guidance](docs/CONFIGURATION.md), [safety and privacy boundaries](docs/SAFETY.md), and [testing guidance](docs/TESTING.md).


## Releases and downloads

[GitHub Releases](https://github.com/itaymatza/android-auto-call-switcher/releases) is the canonical version
history. Each published version has release notes and durable downloadable files, so a GitHub
account is not required just to download the APK.

| Version | Channel | Downloads |
| --- | --- | --- |
| `0.3.0-beta.17` | Android 14+ debug beta | [Latest commit-specific APK, SHA-256, and verification report](https://github.com/itaymatza/android-auto-call-switcher/releases) |
| `0.3.0-beta.10` | Previous Android 14+ debug beta | [Release](https://github.com/itaymatza/android-auto-call-switcher/releases/tag/v0.3.0-beta.10-debug) |
| `0.3.0-beta.9` | Previous Android 14+ debug beta | [Release](https://github.com/itaymatza/android-auto-call-switcher/releases/tag/v0.3.0-beta.9-debug) |
| `0.3.0-beta.7` | Previous Android 14+ debug beta | [Release](https://github.com/itaymatza/android-auto-call-switcher/releases/tag/v0.3.0-beta.7-debug) |
| `0.3.0-beta.6` | Previous Android 14+ debug beta | [Release](https://github.com/itaymatza/android-auto-call-switcher/releases/tag/v0.3.0-beta.6-debug) |
| `0.3.0-beta.5` | Previous Android 14+ debug beta | [Release](https://github.com/itaymatza/android-auto-call-switcher/releases/tag/v0.3.0-beta.5-debug) |

A new debug APK is published for each successful push to `main` on the [Releases page](https://github.com/itaymatza/android-auto-call-switcher/releases). Each file and version includes the source commit ID. Protected debug signing must be configured before publication; the pinned certificate blocks unexpected keys. The first protected-key APK requires a one-time reinstall for users of beta.11 or the initial beta.12 build. Later protected-key builds can update each other in place.

## Download and install the APK

The easiest path is the durable direct download:

1. Open [GitHub Releases](https://github.com/itaymatza/android-auto-call-switcher/releases) and download the `.apk` from the newest `0.3.0-beta.17` debug pre-release.
2. Open the downloaded APK on the Android device.
3. If Android prompts you, temporarily allow **Install unknown apps** for the browser or file
   manager, install the APK, and then disable that permission again.

If Android reports that the package cannot be updated or is incompatible with the installed version, uninstall the existing build first and retry. Uninstalling clears the app's local configuration.

> This is a durable debug/test pre-release for Android 14+, not a production-ready signed release.
> Installation alone does not grant protected Telecom access. Open the app and complete the
> one-time authorization flow below. See the [release notes](docs/DEBUG-PRERELEASE-NOTES.md) and
> [production-readiness gates](docs/PRODUCTION-READINESS.md).

The [Actions build artifact](https://github.com/itaymatza/android-auto-call-switcher/actions/workflows/build.yml)
remains available as a fallback for testing the newest `main` commit, but it requires a GitHub
sign-in, downloads as a ZIP, and expires.

### Authorize call routing in the app

1. Pair the intended car or headset in Android's Bluetooth settings and keep it nearby and powered on.
2. Open Android Auto Call Switcher and select **Allow permissions**.
3. Select **Choose Bluetooth device**, then choose the intended call device.
4. Select **Set up one-time ADB authorization** and follow the displayed steps.
5. Return to the app and tap **Verify**. Continue only after the status says authorization is detected.
6. In **Test safely**, check the selected device’s current call transport. Classic HFP is required; an LE Audio or hearing-aid connection alone is not enough.
7. After a parked test call, use **Record parked call test** to report speaker, microphone, navigation, and media resumption separately. Export the redacted log alongside the captured run; reports do not automatically certify physical audio.

See the exact [authorization and troubleshooting guide](docs/AUTHORIZATION.md), [configuration guidance](docs/CONFIGURATION.md), [research decision](docs/PLATFORM-RESEARCH.md), [diagnostic evidence and system captures](docs/DIAGNOSTIC-EVIDENCE.md), and [parked-car test procedure](docs/TESTING.md).

For repeatable in-place beta upgrades, configure the protected signed build described in
[stable beta signing](docs/SIGNING.md). Ordinary GitHub debug artifacts do not have a stable
certificate across hosted runners; the signed workflow also pins the expected certificate digest.

## Project documentation


See the [product completeness audit](docs/PRODUCT-COMPLETENESS.md) for remaining functionality,
real-device qualification, and repository rename work.

Start with the [FAQ](docs/FAQ.md) and [compatibility guide](docs/DEVICE-COMPATIBILITY.md). Read the
[architecture reference](docs/ARCHITECTURE.md) for the component boundaries and routing state
machine. The [public-release guide](docs/PUBLIC_RELEASE.md) explains what remains visible in a
public repository and how to review a change before pushing it. The
[app metadata guide](docs/APP-METADATA.md) keeps the installed identity, release hub, and reusable
store listing aligned. Contributors should follow
[CONTRIBUTING.md](CONTRIBUTING.md); suspected security, privacy, or safety vulnerabilities belong
in the private reporting path described by [SECURITY.md](SECURITY.md), not a public issue.


## Safety model and limitations


The policy fails closed. It requires Telecom authorization, runtime permissions, a verifiably SIM-backed non-emergency call, a single active call, a configured target present in both Telecom and HFP state, and—when using automatic mode—a verified projection-host state. Evidence may arrive for up to ten seconds. Call-start routing settles for at least 300 ms, with recent activity extending the wait up to 900 ms. The app makes at most one target request per dialing/answer phase (two automatic target requests per call at most) and allows four seconds for exact target HFP/SCO confirmation; it never retries or reasserts the target within that phase. If that window ends in the exact observed split state—Telecom displays the target while the explicitly configured competing endpoint still owns SCO—the app may make one additional, bounded request to display that already-active competing endpoint so the target becomes manually selectable again. Without that configured device, recovery is unavailable because the app cannot safely guess which of several HFP devices owns Android Auto audio. Observed handset, speaker, and wired route callbacks stop a pending automatic guard. Other-Bluetooth routes stop selector recovery; request callbacks alone cannot distinguish an Android Auto startup action from a user choice. An already-submitted platform request cannot be recalled. The explicit manual one-shot bypasses only the automatic toggle and projection gate; it does not bypass authorization, identity, device-presence, or emergency safeguards.


Routing uses API 34+ `requestCallEndpointChange()` with an endpoint object from the latest `onAvailableCallEndpointsChanged()` callback. The deprecated `requestBluetoothAudio(BluetoothDevice)` path has been removed. Because the public endpoint API exposes a name and UUID but no Bluetooth address, the app resolves identity only when the selected device’s current name or alias uniquely matches a live Bluetooth endpoint without a connected peer-name conflict (falling back to the saved name when live labels are unavailable), or when exactly one HFP device and one Bluetooth endpoint exist. Ambiguity fails closed. Endpoint UUIDs are not persisted.

Diagnostics deliberately distinguish `TELECOM_ENDPOINT_CONFIRMED` from
`TARGET_HFP_AUDIO_CONFIRMED`. The former means Telecom selected the intended endpoint object; the
latter also corroborates that the exact locally configured Bluetooth address owns HFP audio/SCO.
Neither replaces a parked physical microphone and speaker test.


> Configure and test only while parked. Do not rely on this project for emergency, safety-critical, or hands-free compliance use cases.


## Verification

Run `bash tools/test-all.sh` for the pure-JVM core, deterministic property, exhaustive eligibility, JSONL replay, and service-callback suites. CI enforces at least 95% line / 90% branch coverage for `:core` and 90% line / 75% branch coverage for the production Telecom-service boundary, excluding framework stubs and test drivers. Host contracts also run on JDK 17 and 21. The pull-request workflow uploads HTML/XML coverage and JUnit reports, assembles the APK, runs Android lint, verifies APK signature and manifest identity, and reruns when a PR is retargeted to `main`. Passing those checks does not establish stable Samsung, Android Auto, Bluetooth, microphone, or speaker behavior; use the parked-car procedure and stability matrix in [testing guidance](docs/TESTING.md).

For repeatable real-device evidence, run
`bash tools/capture_device_run.sh --serial PHONE_SERIAL --scenario outgoing` while parked. The
harness stores only new structured app traces plus explicit physical observations in a local,
Git-ignored run directory and emits `PASS` only when exact HFP audio and all required observations
agree. Each session includes a privacy-safe device/app environment record and deduplicated evidence
snapshots showing Telecom's route beside the actual HFP/SCO owner. Controlled tags classify each
matrix cell, and

```sh
python3 tools/summarize_qualification.py verification/device-runs --require-ready
```

verifies that the batch used one APK/phone build and reached every required passing count.
