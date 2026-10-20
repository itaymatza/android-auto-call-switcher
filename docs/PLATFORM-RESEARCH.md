# Android platform research and decision

Research rechecked against Android developer documentation, SDK 37.0 and AOSP on 2026-10-20. See the [fresh routing decision and alternatives](ROUTING-RELIABILITY-2026-10-20.md).

## Authorization findings

| Approach | Finding | Product decision |
|---|---|---|
| Runtime permission dialog | Impossible. `MANAGE_ONGOING_CALLS` is `signature|appop`, not a dangerous runtime permission. | Detect with `hasManageOngoingCallsPermission()`; do not show a fake permission flow. |
| `CALL_COMPANION_APP` alone | Insufficient. It is a normal manifest permission that makes an `InCallService` eligible to be a calling companion; Telecom still requires the protected AppOp for a non-UI service. | Removed. |
| `CompanionDeviceManager` car association | Not a supported general grant route. Android documents third-party `InCallService` access here for a physical wearable. A generic car association did not grant access in the tested setup. | Removed; old app-owned associations are cleaned up once. |
| Default dialer role | Supported, but inappropriate here. Android requires an `ACTION_DIAL` UI and a complete incoming and ongoing call UI. It would replace the existing Phone UI rather than act as a small router. | Not requested. |
| Privileged/system app | Technically possible only with OEM/system-image control and privileged signing/allowlisting. | Out of scope for a normal APK. |
| One-time ADB AppOps grant | Works with the permission's AppOp gate without changing the dialer or rooting. It must be repeated after reinstall/reset when Android no longer reports the grant. | Primary authorization setup. |
| Shizuku | Can expose shell-level APIs to an app, but adds another installed service, integration code, user trust decision, and usually a restart after reboot when started without root. It does not remove the protected-access boundary. | Not justified for a one-time grant. |
| Root | Could grant or bypass access, but materially weakens device security and may affect OEM security features. | Not recommended; unnecessary. |

AOSP `InCallController` classifies a non-UI service only when it has `CONTROL_INCALL_EXPERIENCE` (system) or an AppOps-permitted `MANAGE_ONGOING_CALLS` grant. Merely declaring the service and `CALL_COMPANION_APP` does not satisfy that gate.

## Routing findings

- API 34 deprecated `getCallAudioState()`, `onCallAudioStateChanged()`, and `requestBluetoothAudio(BluetoothDevice)` in favor of `CallEndpoint` callbacks and `requestCallEndpointChange()`.
- The request must use an endpoint supplied by the current `onAvailableCallEndpointsChanged()` set; apps must not construct their own request endpoint.
- The request outcome reports acceptance or an error. The final route is verified only by `onCallEndpointChanged()`.
- `CallEndpoint` exposes a human-readable name, type, and UUID, but no Bluetooth hardware address. AOSP keeps its UUID-to-address map internally. Public documentation does not promise that the UUID is a durable identifier suitable for persistence.
- The implementation therefore persists the paired Bluetooth identity locally, but resolves it anew against each live endpoint set. A unique endpoint-name match is accepted. A name mismatch is accepted only when exactly one HFP device and one Bluetooth endpoint exist. Duplicate or otherwise ambiguous endpoints fail closed.
- API 37 `onCallEndpointRequested()` lets a non-UI service observe an endpoint request from another in-call service. It does not identify the requester or prove the final route. Call-start activity can extend the short settling window; an external request interrupts reconciliation. After the single target request, passive HFP observation continues without further commands.

## Alternatives rejected

`AudioManager` SCO routing, accessibility automation of Samsung Phone, hidden APIs, notification scraping, and vendor-specific intents are not equivalent supported Telecom endpoint selection. They are brittle, may interfere with media, or require invasive access. No public API was found that lets an ordinary unrelated APK set the per-call Bluetooth endpoint without `InCallService` admission.

## Evidence boundaries

- **Verified by source inspection:** repository behavior, manifest, authorization checks, endpoint resolver, one-shot state machine, tests, and CI configuration.
- **Verified by Android documentation/AOSP:** permission protection level, wearable companion wording, dialer requirements, non-UI AppOps gate, API 34 endpoint contract, deprecations, and API 37 request observation.
- **Verified by automated tests:** pure endpoint matching, routing policy, callback/lifecycle scenarios, compilation, unit tests, and lint when CI is green.
- **Requires real-device verification:** Samsung/One UI admission after AppOps, endpoint labels, Android Auto coexistence, actual microphone/speaker selection, incoming/outgoing calls, and OEM callback timing.
- **Impossible for a normal unprivileged APK:** silently granting `MANAGE_ONGOING_CALLS`, silently becoming default dialer, reliably mapping a public `CallEndpoint` to a Bluetooth MAC address on every OEM, or proving physical audio routing without a device test.

## Primary sources

1. [Android `InCallService` reference](https://developer.android.com/reference/android/telecom/InCallService)
2. [Android `MANAGE_ONGOING_CALLS` permission reference](https://developer.android.com/reference/android/Manifest.permission#MANAGE_ONGOING_CALLS)
3. [Android default phone app requirements](https://developer.android.com/develop/connectivity/telecom/dialer-app)
4. [Android `CallEndpoint` reference](https://developer.android.com/reference/android/telecom/CallEndpoint)
5. [AOSP `InCallService.java`](https://android.googlesource.com/platform/frameworks/base/+/android16-release/telecomm/java/android/telecom/InCallService.java)
6. [AOSP Telecom `InCallController.java`](https://android.googlesource.com/platform/packages/services/Telecomm/+/main/src/com/android/server/telecom/InCallController.java)
7. [AOSP Telecom `CallEndpointController.java`](https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/main/src/com/android/server/telecom/CallEndpointController.java)
