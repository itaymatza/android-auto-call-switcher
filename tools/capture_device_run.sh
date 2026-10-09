#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE="org.carcallrouter.companion"
SCENARIO="other"
SERIAL=""
OUTPUT=""
TAGS=()

usage() {
    cat <<'EOF'
Usage: tools/capture_device_run.sh [--serial SERIAL] [--scenario NAME] [--tag TAG] [--package ID] [--output DIR]

Captures one parked test's structured trace and new redacted app events (when run-as is available).
Scenarios: incoming, outgoing, selector-recovery, override, hold-resume, reconnect, second-call, conference, other.
Repeat --tag to classify a run for the production stability matrix. Use --help to list tags.

Qualification tags:
  cold-start warm-start screen-off post-reboot
  battery-unrestricted battery-optimized battery-restricted
  android-auto-first target-first consecutive after-idle
  projection-unknown hfp-unknown target-disconnect projection-disconnect
  override-speaker override-handset override-wired override-other-bluetooth
EOF
}

while (($#)); do
    case "$1" in
        --serial) SERIAL="${2:?missing serial}"; shift 2 ;;
        --scenario) SCENARIO="${2:?missing scenario}"; shift 2 ;;
        --tag)
            tag="${2:?missing tag}"
            case "$tag" in
                bmw-first) tag="target-first" ;;
                bmw-disconnect) tag="target-disconnect" ;;
            esac
            TAGS+=("$tag"); shift 2 ;;
        --package) PACKAGE="${2:?missing package}"; shift 2 ;;
        --output) OUTPUT="${2:?missing output directory}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

case "$SCENARIO" in incoming|outgoing|selector-recovery|override|hold-resume|reconnect|second-call|conference|other) ;; *)
    echo "Unsupported scenario: $SCENARIO" >&2; exit 2;; esac
for tag in "${TAGS[@]}"; do
    case "$tag" in
        cold-start|warm-start|screen-off|post-reboot|\
        battery-unrestricted|battery-optimized|battery-restricted|\
        android-auto-first|target-first|consecutive|after-idle|\
        projection-unknown|hfp-unknown|target-disconnect|projection-disconnect|\
        override-speaker|override-handset|override-wired|override-other-bluetooth) ;;
        *) echo "Unsupported qualification tag: $tag" >&2; exit 2 ;;
    esac
done
OVERRIDE_TAGS="$(printf '%s\n' "${TAGS[@]}" | grep -Ec '^override-' || true)"
DISCONNECT_TAGS="$(printf '%s\n' "${TAGS[@]}" | grep -Ec '^(target|projection)-disconnect$' || true)"
if [[ "$SCENARIO" == "override" && "$OVERRIDE_TAGS" != "1" ]] \
    || [[ "$SCENARIO" != "override" && "$OVERRIDE_TAGS" != "0" ]]; then
    echo "The override scenario requires exactly one override-* tag; other scenarios allow none." >&2
    exit 2
fi
if [[ "$SCENARIO" == "reconnect" && "$DISCONNECT_TAGS" != "1" ]] \
    || [[ "$SCENARIO" != "reconnect" && "$DISCONNECT_TAGS" != "0" ]]; then
    echo "The reconnect scenario requires exactly one *-disconnect tag; other scenarios allow none." >&2
    exit 2
fi
[[ "$PACKAGE" =~ ^[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+$ ]] || {
    echo "Invalid package ID: $PACKAGE" >&2; exit 2;
}
command -v adb >/dev/null || { echo "adb is required." >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required." >&2; exit 1; }

if [[ -z "$SERIAL" ]]; then
    DEVICE_LIST="$(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')"
    DEVICE_COUNT="$(printf '%s\n' "$DEVICE_LIST" | awk 'NF {count++} END {print count+0}')"
    if [[ "$DEVICE_COUNT" != "1" ]]; then
        echo "Expected exactly one authorized ADB device; found $DEVICE_COUNT. Use --serial." >&2
        exit 1
    fi
    SERIAL="$DEVICE_LIST"
fi
ADB=(adb -s "$SERIAL")
[[ "$("${ADB[@]}" get-state 2>/dev/null)" == "device" ]] || {
    echo "The selected ADB device is not authorized and online." >&2; exit 1;
}
PACKAGE_PATHS="$("${ADB[@]}" shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r')"
grep -q '^package:' <<<"$PACKAGE_PATHS" || {
    echo "Package $PACKAGE is not installed for the current Android user." >&2; exit 1;
}
[[ "$(grep -c '^package:' <<<"$PACKAGE_PATHS")" == "1" ]] || {
    echo "Expected one installed base APK for $PACKAGE; split APK installs are not supported." >&2
    exit 1
}
APK_PATH="${PACKAGE_PATHS#package:}"
if command -v sha256sum >/dev/null; then
    APK_SHA256="$("${ADB[@]}" exec-out cat "$APK_PATH" | sha256sum | awk '{print $1}')"
elif command -v shasum >/dev/null; then
    APK_SHA256="$("${ADB[@]}" exec-out cat "$APK_PATH" | shasum -a 256 | awk '{print $1}')"
else
    echo "sha256sum or shasum is required." >&2
    exit 1
fi
[[ "$APK_SHA256" =~ ^[0-9a-fA-F]{64}$ ]] || {
    echo "Could not hash the installed APK." >&2; exit 1;
}
APP_OPS="$("${ADB[@]}" shell cmd appops get --uid "$PACKAGE" MANAGE_ONGOING_CALLS 2>/dev/null | tr -d '\r')"
grep -Eq 'MANAGE_ONGOING_CALLS: allow|allow' <<<"$APP_OPS" || {
    echo "MANAGE_ONGOING_CALLS is not allowed for $PACKAGE. Complete authorization first." >&2
    exit 1
}

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUTPUT="${OUTPUT:-$ROOT/verification/device-runs/$STAMP-$SCENARIO}"
if [[ -e "$OUTPUT" ]]; then
    echo "Refusing to overwrite existing output path: $OUTPUT" >&2
    exit 1
fi
mkdir -p "$OUTPUT"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

capture_app_log() {
    local destination="$1"
    : >"$destination"
    if "${ADB[@]}" exec-out run-as "$PACKAGE" id >/dev/null 2>&1; then
        for log_name in router.3.log router.2.log router.1.log router.log; do
            "${ADB[@]}" exec-out run-as "$PACKAGE" cat "files/$log_name" \
                >>"$destination" 2>/dev/null || true
        done
    else
        echo "Warning: private app-log access failed; using the current filtered logcat buffer." >&2
        "${ADB[@]}" logcat -d -v threadtime -s CallRouteCompanion:I '*:S' |
            awk 'index($0, "ROUTING_TRACE ") {print}' >"$destination"
    fi
}

capture_app_log "$TEMP_DIR/before.app"
awk 'index($0, "ROUTING_TRACE ") {print}' "$TEMP_DIR/before.app" >"$TEMP_DIR/before.trace"
python3 "$ROOT/tools/analyze_device_trace.py" "$TEMP_DIR/before.trace" --session-ids-only \
    >"$TEMP_DIR/before.sessions"

MANUFACTURER="$("${ADB[@]}" shell getprop ro.product.manufacturer | tr -d '\r\n')"
MODEL="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r\n')"
SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r\n')"
ANDROID_RELEASE="$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r\n')"
SECURITY_PATCH="$("${ADB[@]}" shell getprop ro.build.version.security_patch | tr -d '\r\n')"
BUILD_ID="$("${ADB[@]}" shell getprop ro.build.id | tr -d '\r\n')"
ONE_UI_VERSION="$("${ADB[@]}" shell getprop ro.build.version.oneui | tr -d '\r\n')"
BUILD_FINGERPRINT="$("${ADB[@]}" shell getprop ro.build.fingerprint | tr -d '\r\n')"
VERSION_NAME="$("${ADB[@]}" shell dumpsys package "$PACKAGE" | awk -F= '/versionName=/{gsub(/\r/,"",$2); print $2; exit}')"
VERSION_CODE="$("${ADB[@]}" shell dumpsys package "$PACKAGE" | awk '/versionCode=/{for(i=1;i<=NF;i++) if($i ~ /^versionCode=/){sub(/^versionCode=/,"",$i); print $i; exit}}')"
QUALIFICATION_TAGS="$(IFS=,; echo "${TAGS[*]}")"
cat >"$OUTPUT/device.txt" <<EOF
captured_utc=$STAMP
scenario=$SCENARIO
qualification_tags=$QUALIFICATION_TAGS
manufacturer=$MANUFACTURER
model=$MODEL
android_api=$SDK
android_release=${ANDROID_RELEASE:-unknown}
security_patch=${SECURITY_PATCH:-unknown}
build_id=${BUILD_ID:-unknown}
one_ui_version=${ONE_UI_VERSION:-unknown}
build_fingerprint=$BUILD_FINGERPRINT
package=$PACKAGE
version_name=$VERSION_NAME
version_code=$VERSION_CODE
installed_apk_sha256=$APK_SHA256
manage_ongoing_calls=allow
EOF

echo "Ready for one $SCENARIO test on $MANUFACTURER $MODEL (API $SDK)."
echo "Remain parked. Use only a consenting helper or voicemail; never call an emergency number."
echo "Keep Android Auto and the intended native HFP device connected, then perform the scenario."
read -r -p "Press Enter immediately before starting the call... "
read -r -p "End the call, wait two seconds for the final trace, then press Enter... "

capture_app_log "$TEMP_DIR/after.app"
python3 "$ROOT/tools/extract_new_app_events.py" "$TEMP_DIR/before.app" "$TEMP_DIR/after.app" >"$OUTPUT/app-events.log"
awk 'index($0, "ROUTING_TRACE ") {print}' "$TEMP_DIR/after.app" >"$TEMP_DIR/after.trace"
python3 "$ROOT/tools/analyze_device_trace.py" "$TEMP_DIR/after.trace" \
    --exclude-session-file "$TEMP_DIR/before.sessions" \
    --filtered-trace-output "$OUTPUT/trace.log" >"$OUTPUT/report.txt"
python3 "$ROOT/tools/analyze_device_trace.py" "$OUTPUT/trace.log" \
    --format json >"$OUTPUT/report.json"

ask_observation() {
    local key="$1" prompt="$2" answer
    while true; do
        read -r -p "$prompt [y/n/u]: " answer
        case "$answer" in
            y|Y) echo "$key=yes" >>"$OUTPUT/observations.txt"; return ;;
            n|N) echo "$key=no" >>"$OUTPUT/observations.txt"; return ;;
            u|U) echo "$key=unknown" >>"$OUTPUT/observations.txt"; return ;;
            *) echo "Enter y, n, or u (unknown)." ;;
        esac
    done
}

: >"$OUTPUT/observations.txt"
ask_observation target_hfp_speaker_heard "Was call audio heard through the intended native HFP speaker?"
ask_observation target_hfp_microphone_confirmed "Did the helper confirm the intended native HFP microphone?"
ask_observation android_auto_preserved "Did Android Auto navigation/media remain available?"
if [[ "$SCENARIO" == "override" ]]; then
    ask_observation user_override_respected "Was the manual route override respected without route fighting?"
fi
if [[ "$SCENARIO" == "selector-recovery" ]]; then
    ask_observation split_brain_seen "Did the Phone app show the preferred call device while call audio was actually on Android Auto?"
    ask_observation selector_matched_actual_audio "Did the selector return to Android Auto after the bounded recovery?"
    ask_observation target_became_selectable "Could you then select the preferred call device manually?"
    ask_observation manual_target_speaker_and_mic "Did that manual preferred-device selection move both speaker and microphone?"
fi

VERDICT="FAIL"
if python3 "$ROOT/tools/analyze_device_trace.py" "$OUTPUT/trace.log" \
    --require-pass --require-diagnostics >/dev/null \
    && ! grep -qv '=yes$' "$OUTPUT/observations.txt"; then
    VERDICT="PASS"
fi
{
    echo "verdict=$VERDICT"
    echo "rule=all_new_sessions_have_complete_trace_and_all_required_observations_are_yes"
} >"$OUTPUT/verdict.txt"

echo
cat "$OUTPUT/report.txt"
echo
echo "Combined run verdict: $VERDICT"
echo "Saved redacted evidence to: $OUTPUT"
echo "Review trace.log and app-events.log before sharing; the latter includes non-trace diagnostics."
