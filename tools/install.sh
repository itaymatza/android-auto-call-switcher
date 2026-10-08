#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE="${APP_APPLICATION_ID:-org.carcallrouter.companion}"
VERSION_CODE="${APP_VERSION_CODE:-15}"
VERSION_NAME="${APP_VERSION_NAME:-0.3.0-beta.13}"
SOURCE_ACTIVITY='org.carcallrouter.companion.ui.MainActivity'
if [[ -z "${ANDROID_HOME:-}" ]]; then
    for dir in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [[ -d "$dir" ]]; then export ANDROID_HOME="$dir"; break; fi
    done
fi
ADB="${ADB:-${ANDROID_HOME:+$ANDROID_HOME/platform-tools/adb}}"
ADB="${ADB:-adb}"
SERIAL="${1:-}"
[[ "${SKIP_BUILD:-0}" == 1 ]] || bash "$ROOT/gradlew" "-PAPP_APPLICATION_ID=$PACKAGE" \
    "-PAPP_VERSION_CODE=$VERSION_CODE" "-PAPP_VERSION_NAME=$VERSION_NAME" \
    :core:check :app:ktlintCheck :app:assembleDebug :app:testDebugUnitTest \
    :verification:service-tests:check :app:lintDebug --console=plain
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || { echo "Missing APK: $APK" >&2; exit 1; }
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-${ANDROID_HOME:-}/build-tools/36.0.0}" \
    bash "$ROOT/tools/verify-apk.sh" "$APK" "$PACKAGE" "$VERSION_CODE" "$VERSION_NAME" 34 36 true
if [[ -z "$SERIAL" ]]; then
    DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
    COUNT="$(printf '%s\n' "$DEVICES" | awk 'NF {n++} END {print n+0}')"
    [[ "$COUNT" == 1 ]] || { echo 'Connect one authorized phone or pass its serial number.' >&2; exit 1; }
    SERIAL="$DEVICES"
fi
run_adb() { "$ADB" -s "$SERIAL" "$@"; }
USER_ID="$(run_adb shell am get-current-user | tr -d '\r\n')"
[[ "$USER_ID" =~ ^[0-9]+$ ]] || { echo 'Cannot determine Android user' >&2; exit 1; }
run_adb install --user "$USER_ID" -r "$APK"
run_adb shell cmd appops set --user "$USER_ID" --uid "$PACKAGE" MANAGE_ONGOING_CALLS allow
run_adb shell cmd appops get --user "$USER_ID" "$PACKAGE" MANAGE_ONGOING_CALLS
run_adb shell am start --user "$USER_ID" -n "$PACKAGE/$SOURCE_ACTIVITY"
echo 'Grant runtime permissions, select a target device, test Route now while parked, then enable automation.'
