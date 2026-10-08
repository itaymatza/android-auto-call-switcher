#!/usr/bin/env bash
# Framework-double tests, not an Android emulator or a phone test.
set -euo pipefail
cd "$(dirname "$0")"
source_root="$(cd "${1:-../..}" && pwd)"
base="$source_root/app/src/main/java/org/carcallrouter/companion"
core="$source_root/core/src/main/kotlin/org/carcallrouter/companion/core"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
kotlinc -nowarn -jvm-target 17 src/main/kotlin/stubs/*.kt src/main/kotlin/ServiceTests.kt \
 "$core/CallTimeline.kt" "$core/RoutingPolicy.kt" "$core/RoutingTrace.kt" "$core/EndpointIdentity.kt" "$base/telecom/RouterInCallService.kt" \
 "$base/telecom/AddressedTelecomRouter.kt" "$base/RouterSettings.kt" \
 "$base/SessionBridge.kt" -include-runtime -d "$build/service-tests.jar"
java -jar "$build/service-tests.jar"
