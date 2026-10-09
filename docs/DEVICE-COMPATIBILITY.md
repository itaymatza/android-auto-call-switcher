# Device compatibility

Compatibility depends on Android Telecom, the phone vendor's Bluetooth behavior, the projection
unit, and the target hands-free profile. A brand or model name alone is not enough to claim support.

## Device roles

Android Auto handles navigation, media, and its other functions. The preferred Bluetooth device
handles cellular call speaker and microphone audio. It can be a headset, earbuds, speakerphone,
or car system using classic Bluetooth HFP. LE Audio-only devices and hearing-aid profiles are not
supported by the current HFP/SCO verification backend. Devices offering both LE Audio and classic
HFP must actually expose a connected HFP call endpoint; pairing alone is insufficient.

Selection uses the paired device identity and current call capability; there is no
car-brand allowlist. A media-only Bluetooth speaker cannot supply a call endpoint.

## Foreground transport inspection

The compatibility check distinguishes classic HFP, LE Audio, hearing-aid connections, and a
device observed on both non-classic profiles. A fresh observed non-classic connection without
an observed HFP connection shows a transport warning instead of a ready/active setup card. It
blocks turning automation on until a supported connection is observed or the inspection becomes
unknown; unknown results still cannot satisfy the independent per-call HFP routing guard. An
already-enabled switch remains available so the user can turn automation off.

For LE Audio, the app separately reads the selected connected member's current group and lead
device. Either connected earbud can identify the same group, but a disconnected selected member,
invalid group, unreadable peer, missing lead, or contradictory connected lead does not establish
group identity. Android may retain a disconnected lead; the UI reports that case separately.
Optional group reads run on their own bounded worker, expire, stop with the foreground activity,
and never authorize a call request or change a media route. Exported diagnostics contain salted
aliases and counts, not raw Bluetooth addresses, device names, or group IDs.

Group identity is groundwork for another transport backend, not completed LE Audio routing.
Connection/group observations do not prove active call speaker or microphone audio. Automatic
switching still requires classic HFP. See Android's [LE Audio API contract](https://developer.android.com/reference/android/bluetooth/BluetoothLeAudio)
for group membership and retained lead behavior.

## Required conditions

- Android 14 or newer (API 34+).
- The preferred device appears in Android's active-call audio selector as a Bluetooth endpoint.
- Manually selecting it routes both call speaker and microphone correctly.
- Android Auto remains connected for navigation/media after that manual selection.
- The target can be selected unambiguously in the app. Current device names and aliases are read
  from its exact connected address; conflicts with another connected device fail closed.
- Runtime permissions and the protected Telecom authorization are detected.

## Evidence levels

| Level | Meaning |
|---|---|
| Not evaluated | No parked physical test has been recorded. |
| Proof of concept | At least one call routed correctly, but repeatability is unproven. |
| Qualified beta | One unchanged APK passed the full matrix in `TESTING.md` on that setup. |
| Unsupported | A required condition is absent, or a classified platform limitation prevents safe routing. |

## Current evidence

| Phone / projection / target | Status | Evidence |
|---|---|---|
| Project owner's Samsung phone / aftermarket Android Auto / native 2018 BMW X1 Bluetooth | Proof of concept | Successful physical speaker and microphone routing was observed, but intermittent behavior was also reported. |

This table is deliberately conservative. A successful result on one phone build or head unit does
not imply compatibility with another. Re-run the baseline after an Android or major One UI update.

## Reporting a result

Use the repository's **Device compatibility report** issue form. Never include phone numbers, raw
Bluetooth addresses, ADB serials, unreviewed logs, or screenshots containing account details. A
useful report includes the Android version, phone model family, projection type, target type, app
version, scenario, result, and the app's redacted reason code.
