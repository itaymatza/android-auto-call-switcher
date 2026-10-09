# Direct-download debug beta

This pre-release makes the current Android Auto Call Switcher beta installable as a direct APK download.

## Install

1. Download the `.apk` attached to this release.
2. Open the APK on an Android 14+ phone and allow the browser or file manager to install unknown
   apps when prompted.
3. Open Android Auto Call Switcher and complete the guided permissions, target-device selection, and
   one-time ADB authorization.
4. Test the manual one-shot route while safely parked before enabling automatic routing.

## Important beta limitations

- This is a debug-signed testing build, not a production-ready release.
- Its signing certificate is pinned and recorded in **apk-verification.txt**. Builds published after
  the protected debug signing setup use the same key and can update each other in place. Older
  beta.11 and beta.12 builds used temporary runner keys: the first protected-key build cannot
  replace them. Uninstall the older app once, then reinstall, reconfigure, and repeat the one-time
  authorization. The separately protected non-debug release certificate also differs.
- Real-car stability qualification is still incomplete. Never use emergency calls for testing and
  do not interact with the app while driving.

The attached `.sha256` file verifies the APK bytes. The GitHub artifact attestation links the APK
to the workflow and source commit that produced it.

## What changed in beta.14

- Starts one bounded preferred-device routing transaction during outgoing dialing, then rechecks fresh
  audio evidence at answer without replacing a pending request or fighting manual selections.
- Samples HFP ownership throughout the call, including after a failed startup request.
- Records observed call duration and call/dialing/answer-relative event timing, late service
  binding, observation gaps and audio loss. Request acceptance alone does not count as success.
- Maps research questions to exported evidence, adds Bluetooth power and microphone-mute
  diagnostics, and labels unavailable settings and unknown external request origins.
- Adds deterministic tests for answer takeover, delayed callbacks, absent target audio,
  manual overrides and incomplete observation. Physical microphone/speaker behavior and
  seamless Samsung/Android Auto handover still require controlled real-device testing.
