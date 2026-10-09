# Telecom authorization

Android Auto Call Switcher can keep your existing Phone app as the default dialer, but Android does
not let an ordinary installed app control an ongoing call automatically. The
protected `MANAGE_ONGOING_CALLS` AppOp must be allowed once for this package.

## Why an in-app button cannot grant it

`MANAGE_ONGOING_CALLS` is a protected permission/AppOp. A normal runtime
permission dialog cannot grant it. Android reserves the supported automatic
paths for the default dialer, privileged/system software, and eligible wearable
companion apps. A Companion Device Manager association with a car or generic
Bluetooth device does not grant this AppOp.

The manifest role `CALL_COMPANION_APP` declares what this app is built to do; it
does not authorize ongoing-call management by itself. The app therefore checks
the AppOp and reports success only after Android returns `allow`.

| Configuration | Samsung Phone remains default | Authorization result |
| --- | --- | --- |
| Install and visible permission dialogs only | Yes | Insufficient |
| Generic BMW companion-device association | Yes | Does not grant the AppOp |
| One-time ADB AppOp grant | Yes | Supported by this project |
| Make this app the default dialer | No | Outside the project's design |

## Authorize from macOS, Linux, or Windows

Install Android Platform Tools, enable Developer options and USB debugging on
the phone, connect and unlock it, then approve the phone's USB-debugging prompt.

List connected targets:

```sh
adb devices
```

The phone must say `device`, not `unauthorized`. If an emulator or another
device is also listed, use the phone serial in every command:

```sh
adb -s PHONE_SERIAL shell cmd appops set --uid org.carcallrouter.companion MANAGE_ONGOING_CALLS allow
adb -s PHONE_SERIAL shell cmd appops get --uid org.carcallrouter.companion MANAGE_ONGOING_CALLS
```

Expected verification output:

```text
Uid mode: MANAGE_ONGOING_CALLS: allow
```

No output from the `set` command normally means it succeeded. Return to Car
Call Router, tap **Verify**, and refresh Diagnostics. It should show:

```text
Runtime permissions: true
Telecom authorization: true
```

An old `AUTH_MISSING` line in Recent diagnostic events is history, not the
current state. The Technical status values at the top are authoritative.

## Continue without a computer

An on-device ADB client such as LADB can run the same operation after Android's
Wireless debugging pairing flow. Because that client already opens an Android
shell, enter the command without the leading `adb shell`:

```sh
cmd appops set --uid org.carcallrouter.companion MANAGE_ONGOING_CALLS allow
cmd appops get --uid org.carcallrouter.companion MANAGE_ONGOING_CALLS
```

This is still a developer authorization step outside the app. Stock Android
does not expose a safe API that lets this app grant the AppOp to itself.

## Lifecycle and revocation

Reinstalling the APK, clearing app data, changing the application ID, or some
system updates can require authorization again. The app should never report
success unless the current AppOp check returns allowed.

To return the package to Android's default AppOp state:

```sh
adb -s PHONE_SERIAL shell cmd appops set --uid org.carcallrouter.companion MANAGE_ONGOING_CALLS default
```

Uninstalling the app also removes its package-specific state.

## Troubleshooting

- `unauthorized`: unlock the phone and accept its USB-debugging fingerprint.
- `more than one device/emulator`: add `-s PHONE_SERIAL` to the command.
- `offline` emulator: it can remain listed if the real phone is selected with
  `-s`; alternatively close the emulator.
- `No UID for ...`: install the APK first and confirm the application ID.
- App still says unauthorized: force-stop and reopen it, tap **Verify**, then
  repeat the `get` command to confirm the AppOp was not reset.

After setup, disable USB debugging and Wireless debugging unless they are
needed for development.

## Platform references

- [Android `InCallService` documentation](https://developer.android.com/reference/android/telecom/InCallService)
- [Android companion-device pairing overview](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)
- [Android Debug Bridge documentation](https://developer.android.com/tools/adb)
