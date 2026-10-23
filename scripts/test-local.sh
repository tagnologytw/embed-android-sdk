#!/usr/bin/env bash
# Runs the SDK checks locally (replaces the GitHub Actions workflow, removed to
# save CI cost): compile the SDK and demo, then run the SDK unit tests.
#
#   scripts/test-local.sh
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew \
  :embed-android-sdk:compileDebugKotlin \
  :demo:compileDebugKotlin \
  :embed-android-sdk:testDebugUnitTest \
  --no-daemon

echo "==> All local checks passed"
