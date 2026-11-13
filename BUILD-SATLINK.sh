#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
bash gradlew :app:assembleDebug --console=plain
printf '%s\n' 'APK: app/build/outputs/apk/debug/app-debug.apk'
