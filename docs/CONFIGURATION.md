# Configuration guidance

## Build identity

The Android Gradle module accepts three optional Gradle properties. They may be supplied through a local Gradle properties file outside the repository or as `-P` arguments in a build command.

| Property | Default | Purpose |
|---|---|---|
| `APP_APPLICATION_ID` | `org.carcallrouter.companion` | Installable Android application ID. Choose an ID controlled by the build owner. |
| `APP_VERSION_CODE` | `20` | Monotonically increasing Android version code. |
| `APP_VERSION_NAME` | `0.3.0-beta.18` | Human-readable version string. |

Example:

```sh
bash gradlew \
  -PAPP_APPLICATION_ID=example.callroute \
  -PAPP_VERSION_CODE=16 \
  -PAPP_VERSION_NAME=0.3.0-beta.18 \
  :app:assembleDebug
```

Changing the application ID creates a distinct Android application. Any protected authorization must therefore be performed for the exact application ID installed on the device. The generic source namespace is intentionally fixed; changing it requires an explicit source-package migration, not a Gradle property.

## Runtime choices

At runtime, a person configuring a test device chooses the target Bluetooth call device from already paired devices. The app stores the chosen address and display name in application-private `SharedPreferences`; neither value is compiled into the source tree or transmitted by the app. If the Android Auto head unit is another paired HFP call device, the user can optionally identify it as the competing device. This permits one bounded request to restore the manual call selector only when Telecom displays the target but that exact competing device still owns HFP audio after the target request. Without a configured competitor, the app cannot safely restore the selector in that split state. It never retries or reasserts the target.

The master automation toggle is disabled by default. It cannot be enabled until the target is selected, the required runtime permissions are granted, and the operating system reports that the protected ongoing-call authorization is present. A manual one-shot is available only while Telecom has bound the service, and it retains all authorization, call-safety, and target-identity requirements.

The permissions button is an explicit user action. Before distributing a customized build, make sure its user-facing explanation clearly states why it requests Bluetooth connection and phone-number access, and that a denial leaves routing disabled rather than blocking the rest of the application. Android recommends requesting runtime permissions in the context of the action that needs them and degrading gracefully when they are denied. [1]

## Call-routing authorization

Android exposes no runtime-permission dialog for this utility. `MANAGE_ONGOING_CALLS` is `signature|appop`; the documented companion route is for physical wearable devices, not arbitrary paired cars. The app therefore does not create a misleading car association or request the default-dialer role. Samsung Phone can remain the default dialer. [2][3]

After selecting a target, open **Set up one-time ADB authorization**. Enable USB debugging, connect and unlock the phone, approve its debugging prompt, then list targets:

```sh
adb devices
```

The physical phone must say `device`, not `unauthorized`. If an emulator or another target is also listed, select the phone serial explicitly:

```sh
adb -s PHONE_SERIAL shell cmd appops set --uid org.carcallrouter.companion MANAGE_ONGOING_CALLS allow
adb -s PHONE_SERIAL shell cmd appops get --uid org.carcallrouter.companion MANAGE_ONGOING_CALLS
```

Use the actual configured application ID if the build overrides the default. The expected verification output includes:

```text
Uid mode: MANAGE_ONGOING_CALLS: allow
```

Return to the app and tap **Verify**. The app advances only if `TelecomManager.hasManageOngoingCallsPermission()` returns true. An older `AUTH_MISSING` diagnostic event remains historical; the current Technical status is authoritative.

Uninstall/reinstall, a changed application ID, AppOps reset, or some OS upgrades can require repeating the command. To revoke the grant:

```sh
adb -s PHONE_SERIAL shell cmd appops set --uid org.carcallrouter.companion MANAGE_ONGOING_CALLS default
```

An on-device ADB client can run the same `cmd appops` operation after Wireless debugging pairing, without the leading `adb shell`. This remains an external developer authorization step: stock Android does not provide an API that lets the app grant the protected AppOp to itself.

Older builds created inappropriate companion associations. The current build removes associations owned by this app once during migration; they are not used for readiness or routing. See [AUTHORIZATION.md](AUTHORIZATION.md) for complete troubleshooting and lifecycle details.

## Projection integration

Automatic mode uses the AndroidX car-app host-provider protocol as an optional projection-state gate. The monitor deliberately avoids inferring projection state from Bluetooth names, Wi-Fi networks, package process state, or broadcast extras. A missing or temporarily unknown projection state freezes new automatic route requests until fresh evidence arrives; a confirmed disconnect stops an active guard and tears down Bluetooth HFP monitoring. A later verified projection connection creates a fresh HFP observer. The manual one-shot has an explicit projection-gate bypass for parked testing only.

## References

[1]: https://developer.android.com/training/permissions/requesting "Android Developers: Request runtime permissions"
[2]: https://developer.android.com/reference/android/telecom/InCallService#access-to-incallservice-for-wearable-devices "Android Developers: InCallService access for wearable devices"
[3]: https://developer.android.com/reference/android/Manifest.permission#MANAGE_ONGOING_CALLS "Android Developers: MANAGE_ONGOING_CALLS protection level"
