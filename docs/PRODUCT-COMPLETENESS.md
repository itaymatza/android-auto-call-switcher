# Product completeness audit

The product is **Android Auto Call Switcher**: select the Bluetooth device for cellular call
speaker and microphone audio while Android Auto continues its other functions. This audit lists
what is implemented, what needs device evidence, and what remains a platform/product gap. It is
not a claim of universal compatibility or production readiness.

| Surface / behavior | Status | Remaining action |
| --- | --- | --- |
| Launcher, app copy, Gradle project, store title | Renamed in PR #69 | Inspect fresh install and update on a real phone. |
| README, current release notes, social preview, future APK/artifact names | Follow-up aligns current branding | Existing historical assets/notes remain historical. Upload the new social preview in GitHub Settings. |
| GitHub repository name | Still `itaymatza/car-call-router` | Rename to `itaymatza/android-auto-call-switcher`; the connector has no repository-settings write operation. |
| Repository description/topics | Requires Settings review | Description: “Choose the Bluetooth device for cellular calls while Android Auto handles navigation and media.” Topics: `android`, `android-auto`, `bluetooth`, `hfp`, `telecom`, `kotlin`. |
| Canonical links, badges, clone URL, signing helper repository argument | Migration prepared separately | Switch to the new URL with the actual repository rename; verify redirects and release links. |
| Installed package, namespace, signing material | Intentionally stable | Keep `org.carcallrouter.companion`, existing certificate, keystore locations and key aliases. Renaming these is not branding cleanup. |
| Old BMW/car labels in historical evidence and compatibility aliases | Intentionally retained | Do not rewrite physical evidence or break old qualification captures. Current runtime selection uses configured device identity. |
| Exact call speaker/microphone selection | Implemented for classic HFP cellular calls | OEM/device tests must verify both physical paths; endpoint display alone is insufficient. |
| Incoming, outgoing, late binding, manual route protection | Deterministic regression coverage | Qualify on device with app closed, screen off, idle, reboot, permission changes and competing route controllers. |
| Android Auto navigation/media continuity | No media/global routing mutation; parked report available | Test navigation during the call and media resumption afterward. Normal call-time media pausing is not a failure. |
| Parked report consumption | Follow-up capture gate | Reject failed, unchecked, or malformed new reports. Positive reports do not replace Telecom/HFP or operator evidence gates. |
| LE Audio/hearing-aid devices | Connection preflight only | Group-aware identity and transport-specific verification need a separate backend and device qualification. No HFP evidence can be inferred from an LE connection. |
| VoIP / self-managed calls | Unsupported | Review service visibility and safe eligibility separately; do not relax the SIM/emergency classifier. |
| Installation-only / phone-only authorization | Unresolved | Existing protected access requires one-time ADB; preserve the Phone app. In-app self-grant is not available in the current architecture. |
| Protected signing / in-place upgrade | Workflows implement checks | Verify secrets/certificate gate, actual published APK, retained settings and AppOp on a physical phone. Do not distribute a temporary-key local APK as an update. |
| API 37 request-callback dispatch | Forward signature covered by host tests | Validate on Android 17; SDK stub/host tests do not prove platform dispatch. |
| Production claim | Beta only | Complete the unchanged-APK stability matrix, classify failures and verify signed non-debuggable release. |

## Repository rename procedure

Prepare link changes before changing Settings. The proposed slug is `android-auto-call-switcher`.
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
