# Product completeness audit

The product is **Android Auto Call Switcher**: select the Bluetooth device for cellular call
speaker and microphone audio while Android Auto continues its other functions. This audit lists
what is implemented, what needs device evidence, and what remains a platform/product gap. It is
not a claim of universal compatibility or production readiness.

| Surface / behavior | Status | Remaining action |
| --- | --- | --- |
| Launcher, app copy, Gradle project, store title | Renamed in PR #69 | Inspect fresh install and update on a real phone. |
| README, current release notes, social preview, future APK/artifact names | Aligned in PR #70; Settings social preview uploaded | Existing historical assets/notes remain historical. |
| GitHub repository name | Canonical slug `itaymatza/android-auto-call-switcher` | Renamed in GitHub Settings on 2026-10-09; repository identity and history retained. |
| Repository description/topics | Updated in GitHub Settings | Description names the product and explicitly covers selected Bluetooth call speaker/microphone plus Android Auto navigation/media. Vendor-specific topic removed; generic Android, Bluetooth HFP, Telecom and Kotlin topics retained. |
| Canonical links, badges, clone URL, signing helper repository argument | Canonical URL migration | README, app About/support, issue-template and signing-helper links use the new slug; local `origin` updated. |
| Installed package, namespace, signing material | Intentionally stable | Keep `org.carcallrouter.companion`, existing certificate, keystore locations and key aliases. Renaming these is not branding cleanup. |
| Old BMW/car labels in historical evidence and compatibility aliases | Intentionally retained | Do not rewrite physical evidence or break old qualification captures. Current runtime selection uses configured device identity. |
| Exact call speaker/microphone selection | Implemented for classic HFP cellular calls | OEM/device tests must verify both physical paths; endpoint display alone is insufficient. |
| Incoming, outgoing, late binding, manual route protection | Deterministic service coverage plus production Bluetooth monitor lifecycle checks | Qualify on device with app closed, screen off, idle, reboot, permission changes and competing route controllers. |
| Android Auto navigation/media continuity | No media/global routing mutation; parked report available | Test navigation during the call and media resumption afterward. Normal call-time media pausing is not a failure. |
| Parked report consumption | Capture gate and batch summary both check raw reports | Reject failed, unchecked, or malformed reports even beside a saved `PASS` verdict or stale derived JSON. Current captures require their raw app-event file; legacy captures without reports keep their existing requirements. Positive reports do not replace Telecom/HFP or operator evidence gates. |
| LE Audio/hearing-aid devices | Distinct foreground transport checks and LE group/lead inspection; unsupported transport readiness warning | Automatic switching remains unsupported. Group observations are not active audio verification. A separate transport backend and physical speaker/microphone qualification remain open; hearing-aid identity/verification is still separate. |
| VoIP / self-managed calls | Scope/visibility review completed; unsupported | Self-managed visibility remains disabled in the manifest. Explicit unsupported flags reject before protected queries; managed non-SIM accounts also reject. Production-classifier tests cover both. Routing support still needs a separate eligibility/visibility design; enabling the metadata alone would not support every VoIP app. |
| Installation-only / phone-only authorization | Platform-constrained under the current product requirements | Existing protected access requires one-time ADB; preserve the Phone app. In-app self-grant is not available in the current architecture. |
| Protected signing / in-place upgrade | Workflows implement checks | Verify secrets/certificate gate, actual published APK, retained settings and AppOp on a physical phone. Do not distribute a temporary-key local APK as an update. |
| API 37 request callback | Actual override compiled against stable API 37.0; host callback-order tests | Validate dispatch on Android 17; compilation and host tests do not prove platform dispatch. |
| Production claim | Beta only | Complete the unchanged-APK stability matrix, classify failures and verify signed non-debuggable release. |

The [fresh beta.18 review](REVIEW-2026-10-09.md) records additional session isolation, manual-control, setup, diagnostics and publication fixes, with regression evidence.

## Remaining engineering and qualification work

External integration and physical gates are tracked in [issue #76](https://github.com/itaymatza/android-auto-call-switcher/issues/76).
Closed code gaps are listed here alongside their verification requirements.

| Gap | Concrete next requirement |
| --- | --- |
| Call-safety and authorization query latency | Implemented: separate bounded zero-queue workers; explicit pending/unsafe/unknown states; 750 ms freshness, exact call/account/handle invalidation, permission rechecks, deadline and late-callback tests. No unknown result authorizes a request. |
| HFP evidence and live identity | Implemented: independent bounded SCO/label/power workers; event-generation invalidation; automatic recheck after superseded work drains; independent freshness; explicit complete-label admission and no saved-name routing. Close clears evidence and late logging; replaced proxies are released. Production HFP, core identity and service scenarios cover these paths. |
| Audio framework diagnostic isolation | Implemented: audio mode and optional device/mute diagnostics use separate bounded zero-queue workers and independent 750 ms freshness. Close clears evidence and drops late completions/logging. Production-class tests cover stalls, saturation, stale results, unavailable services and redacted inventory. Physical paths still require device qualification. |
| Projection provider latency | Implemented: provider query, cursor access and close run off-owner; generation-invalidated and delayed positive results cannot authorize routing; lifecycle and stuck-query tests. |
| API 37 compile boundary | Implemented: supported AGP 9.2.1 / Gradle 9.4.1 toolchain and `platforms;android-37.0`; compiler-checked public override. Runtime Android 17 dispatch remains device qualification. |
| LE Audio routing | The compile SDK 37.0 public API exposes group/member/lead inspection, but not an equivalent of classic HFP `isAudioConnected`. Define independent active-route evidence and group identity, then qualify both physical speaker and microphone. Do not treat a group lead or device inventory as audio confirmation. |
| Hearing-aid identity | Compile SDK 37.0 public `BluetoothHearingAid` exposes connections, but not HiSync/group or device-side getters. Do not build a backend on hidden/system APIs. An exact endpoint identity and separate audio-evidence design are required. |
| Phone-only authorization | Android documents the companion permission route for physical wearables. A general car/headset utility cannot self-grant this protected access while preserving the existing default Phone app. The automotive-projection companion profile is also restricted to non-third-party applications. Stock installation-only onboarding therefore remains blocked; a privileged provisioning or supported platform integration is a separate product decision. |
| Device qualification | Run one unchanged signed APK through speaker/microphone, Android Auto navigation/media, background/sleep/reboot and in-place-upgrade tests. Host tests cannot complete these gates. |

Primary references: [InCallService companion access](https://developer.android.com/reference/android/telecom/InCallService),
[Telecom self-managed visibility](https://developer.android.com/reference/android/telecom/TelecomManager#METADATA_INCLUDE_SELF_MANAGED_CALLS),
[LE Audio API](https://developer.android.com/reference/android/bluetooth/BluetoothLeAudio), and
[hearing-aid API](https://developer.android.com/reference/android/bluetooth/BluetoothHearingAid), and
[automotive companion permission](https://developer.android.com/reference/android/Manifest.permission#REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION).
SDK API availability above was also checked against the project's actual `platforms/android-37.0/android.jar`.

## Repository rename procedure

**Migration completed:** GitHub Settings renamed `car-call-router` to
`android-auto-call-switcher` on 2026-10-09. The old slug must remain available for GitHub
redirects. The repository description, generic topics, README banner, and Settings social
preview now use the current product identity.

For future renames, prepare link changes before changing Settings.
Keep the repository itself: do not create a replacement repository or migrate issues/releases.
GitHub redirects ordinary repository/web/Git links after a rename, but hosted action consumers
and Pages have separate limitations. This project does not publish a reusable GitHub Action or
have a Pages publishing workflow. Confirm Settings before assuming there is no Pages site.

After the admin rename, merge the prepared link migration, change `origin` to
`https://github.com/itaymatza/android-auto-call-switcher.git`, and verify repository identity,
PR/issue history, release downloads, badges, About links, workflow publication, signing helpers,
and the existing protected environments. Do not recreate the old repository slug: that can
remove the redirects. Do not regenerate signing keys as part of the rename.

Reference: [GitHub repository rename documentation](https://docs.github.com/en/repositories/creating-and-managing-repositories/renaming-a-repository).
