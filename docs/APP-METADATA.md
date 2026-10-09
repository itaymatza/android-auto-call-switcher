# App metadata

The installed application, release titles, and reusable store listing use the product identity
**Android Auto Call Switcher**. Repository rename/link migration to `android-auto-call-switcher` is pending admin Settings access.
The package remains `org.carcallrouter.companion` for in-place update continuity.
See [the completeness audit](PRODUCT-COMPLETENESS.md) for the full migration checklist. The app is an independent utility.

| Surface | Source of truth |
| --- | --- |
| Android launcher name and description | `app/src/main/res/values/strings.xml` |
| Android launcher icon | Adaptive and monochrome resources under `app/src/main/res/` |
| Package and version | `app/build.gradle.kts` |
| Version history and downloads | GitHub Releases |
| Store title, descriptions, and release notes | `fastlane/metadata/android/en-US/` |
| Repository search description and topics | GitHub repository settings |

The app deliberately has no `INTERNET` permission. Its About and support links are opened through
the user's browser with standard HTTPS intents. The manifest disables cleartext traffic and backup,
uses resource-backed user-visible metadata, and supplies adaptive, round, and themed launcher icon
variants.

When changing the product name, package, version, or release channel, update every applicable
surface above in the same pull request. `tools/check_version_consistency.py` protects the critical
cross-file markers in CI.
