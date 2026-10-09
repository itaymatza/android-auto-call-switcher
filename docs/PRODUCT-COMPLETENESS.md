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
| Incoming, outgoing, late binding, manual route protection | Deterministic regression coverage | Qualify on device with app closed, screen off, idle, reboot, permission changes and competing route controllers. |
| Android Auto navigation/media continuity | No media/global routing mutation; parked report available | Test navigation during the call and media resumption afterward. Normal call-time media pausing is not a failure. |
| Parked report consumption | Capture gate and batch summary both check raw reports | Reject failed, unchecked, or malformed reports even beside a saved `PASS` verdict or stale derived JSON. Current captures require their raw app-event file; legacy captures without reports keep their existing requirements. Positive reports do not replace Telecom/HFP or operator evidence gates. |
| LE Audio/hearing-aid devices | Connection preflight only | Group-aware identity and transport-specific verification need a separate backend and device qualification. No HFP evidence can be inferred from an LE connection. |
| VoIP / self-managed calls | Unsupported | Review service visibility and safe eligibility separately; do not relax the SIM/emergency classifier. |
| Installation-only / phone-only authorization | Unresolved | Existing protected access requires one-time ADB; preserve the Phone app. In-app self-grant is not available in the current architecture. |
| Protected signing / in-place upgrade | Workflows implement checks | Verify secrets/certificate gate, actual published APK, retained settings and AppOp on a physical phone. Do not distribute a temporary-key local APK as an update. |
| API 37 request-callback dispatch | Forward signature covered by host tests | Validate on Android 17; SDK stub/host tests do not prove platform dispatch. |
| Production claim | Beta only | Complete the unchanged-APK stability matrix, classify failures and verify signed non-debuggable release. |

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
