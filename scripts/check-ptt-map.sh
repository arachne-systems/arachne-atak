#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .cache
kotlinc android/app/src/main/kotlin/dev/datafabric/atak/PttTalkerState.kt \
  android/app/src/test/kotlin/dev/datafabric/atak/PttTalkerMapCheck.kt \
  -include-runtime -d .cache/ptt-map-check.jar
java -jar .cache/ptt-map-check.jar
