#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
gradle :app:assembleDebug --console=plain
printf '%s\n' 'APK: app/build/outputs/apk/debug/'
