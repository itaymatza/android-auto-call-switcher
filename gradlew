#!/usr/bin/env bash
# This is a source-only launcher, NOT an altered/vendored Gradle wrapper JAR.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
if [[ -z "${JAVA_HOME:-}" && -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]]; then
    export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi
bash "$ROOT/tools/bootstrap-gradle.sh"
exec "$ROOT/.tools/gradle-8.13/bin/gradle" -p "$ROOT" "$@"
