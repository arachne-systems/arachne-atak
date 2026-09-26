#!/usr/bin/env bash
# Run the portable check mains after :app:compileCivDebugUnitTestKotlin. No device or JNI calls.
set -euo pipefail
cd "$(dirname "$0")/.."
fabric_kotlin_home="${KOTLIN_HOME:-$HOME/.sdkman/candidates/kotlin/current}"
fabric_classpath="android/app/build/tmp/kotlin-classes/civDebug:android/app/build/tmp/kotlin-classes/civDebugUnitTest:$fabric_kotlin_home/lib/kotlin-stdlib.jar"
for fabric_check in PttTalkerMapCheck CotFeedProjectionCheck ControlDrainCheck DirectedTransferCheck JoinRetryBackoffCheck ResetConfirmationCheck ResourceFilesCheck; do
    java -Xmx32m -cp "$fabric_classpath" "dev.arachne.atak.${fabric_check}Kt"
done
