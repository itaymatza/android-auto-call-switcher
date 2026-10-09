#!/usr/bin/env bash
# Run the same deterministic JVM checks recorded in verification/current/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
command -v java >/dev/null || { echo "A JDK is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
LOGS="$ROOT/verification/current"
mkdir -p "$LOGS"
java -version > "$LOGS/toolchain.log" 2>&1
bash "$ROOT/gradlew" :app:ktlintCheck :core:check :verification:service-tests:check :verification:bluetooth-tests:check :verification:call-safety-tests:check \
    --console=plain 2>&1 | tee "$LOGS/core-tests.log"
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s "$ROOT/tools/tests" -p 'test_*.py' \
    2>&1 | tee "$LOGS/tool-tests.log"
