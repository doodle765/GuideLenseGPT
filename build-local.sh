#!/usr/bin/env bash
set -euo pipefail
if command -v gradle >/dev/null 2>&1; then
  exec gradle assembleDebug --stacktrace "$@"
fi
if [[ -x "${ANDROID_HOME:-}/cmdline-tools/latest/bin/sdkmanager" ]]; then
  echo "Gradle is not installed. Open this project in Android Studio, or install Gradle 8.7." >&2
else
  echo "Gradle 8.7 is required. Open the project in Android Studio or install Gradle 8.7." >&2
fi
exit 1
