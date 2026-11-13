#!/usr/bin/env bash
# Runs every logic test. These use small Android/Gecko substitutes; they do not replace a real APK build.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
java tests/ValidateJava.java app/src/main/java/com/satlink/geckoviewtest/*.java
for test in tests/*.test.cjs; do node "$test"; done
for test in tests/*_test.py; do python3 "$test"; done
echo "ALL TESTS PASSED"
