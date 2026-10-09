# Public-release guide

## What public release means

A public GitHub repository exposes its current files, commit history, GitHub Actions logs, issue and discussion content, pull requests, release assets, and public forks. Removing a file from the current branch does not remove it from older commits, existing clones, forks, caches, notifications, or third-party archives. Treat every pushed commit as public information.

> The current source tree is designed to contain no real device configuration, exported diagnostic data, signing material, credential, APK, or local Android SDK configuration. This statement applies to the current tree only, not necessarily to every historical commit or external copy.

GitHub recommends secret scanning, push protection, and an explicit security policy for public repositories. [1]

## Contents that must remain local

Do not commit any Bluetooth address or device name obtained from a real device. Do not commit screenshots, call details, phone numbers, pairing records, `dumpsys` output, exported diagnostics, test captures, APKs, keystores, certificates, `.env` files, `local.properties`, tokens, or credentials. The repository `.gitignore` excludes common local and generated files, but ignore rules do not replace a review of staged changes.

If a secret or sensitive file is committed, rotate the secret or mitigate the exposure first. Then remove the material from the current branch and assess whether a history rewrite is appropriate. A force-push can invalidate collaborator clones and still cannot retract previously copied data.

## Pre-push checklist

Before publishing a change, run the deterministic suites and review the exact staged diff.

```sh
bash tools/test-all.sh
git status --short
git diff --cached --check
git diff --cached
```

Release trust comes from the protected signing key, the published APK digest, GitHub's artifact
attestation, immutable action revisions, and review of the tagged source. A checksum manifest
stored and updated in the same source tree is intentionally not used as a trust boundary.

For a full Android verification where an Android SDK is available, run:

```sh
bash gradlew :app:assembleDebug :app:testDebugUnitTest :verification:service-tests:run \
  :app:lintDebug --stacktrace --console=plain
bash tools/verify-apk.sh app/build/outputs/apk/debug/app-debug.apk \
  org.carcallrouter.companion 19 0.3.0-beta.17 34 36 true
```

The APK verifier fails closed on a signature error, unexpected package or version, SDK drift,
missing required permissions, or an unexpected debuggable state. It prints the signing-certificate
and whole-APK SHA-256 digests. CI saves the same record beside the debug APK artifact.

A successful build or CI job does not demonstrate protected-permission admission, call routing, Bluetooth audio, microphone behavior, or projection coexistence on a physical device.

## Release checklist

A release candidate should have a clean working tree, passing deterministic tests, a passing Android build and lint job, reviewed dependency changes, and documentation that matches the source. Keep build artifacts out of the repository. If distributing an APK, sign it outside the repository with controlled signing material and publish its checksum separately from the source tree.

After signing a non-debuggable release APK, verify the exact release identity before distribution:

```sh
REPORT_FILE=release-apk-verification.txt \
  bash tools/verify-apk.sh signed-release.apk \
  YOUR_APPLICATION_ID VERSION_CODE VERSION_NAME 34 36 false
```

Publish the generated verification record with the APK. It is evidence about that exact byte
sequence and certificate, not proof of physical-device stability. Never store the keystore,
password, or signing configuration in this repository.

Use a new application ID that the distributor controls when creating a separately distributed app. Document the application ID, version code, version name, supported Android versions, and device-test limitations for that release. Do not imply that a generic source repository has been validated on a particular phone, vehicle, headset, dialer, or projection environment unless reproducible evidence is published without exposing personal data.

## Repository settings to review

Private vulnerability reporting is enabled so potential vulnerabilities can be reported without creating a public issue. The default build jobs have read-only permissions and upload the generated debug APK as an expiring GitHub Actions artifact named `android-auto-call-switcher-debug-apk`; they do not upload phone diagnostics. Only the downstream, main-branch publication job has narrowly scoped release and attestation permissions. It runs after host and Android checks pass, and publishes a clearly labeled debug pre-release with a checksum and verification record. That durable debug APK improves access but does not satisfy the protected-signing or real-device qualification gates for a stable release. Repository administrators should periodically review collaborator access, branch protection, fork policy, issue moderation, Actions permissions, secret-scanning alerts, and the visibility of releases or deployment environments.

Dependabot checks Gradle and GitHub Actions dependencies weekly with a bounded number of open pull
requests. Every update remains an explicit review decision and must pass the normal build, test,
lint, APK-verification, and CodeQL checks before merge.

## References

[1]: https://docs.github.com/en/code-security/getting-started/securing-your-repository "GitHub Docs: Securing your repository"
