#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
: "${FABRIC_TAK_SDK:?Set FABRIC_TAK_SDK to the separately obtained ATAK-CIV 5.8.0.4 SDK}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
test -f "$FABRIC_TAK_SDK/main.jar"
for fabric_abi in x86_64 arm64-v8a; do
    case "$fabric_abi" in
        x86_64) fabric_target=x86_64-linux-android ;;
        arm64-v8a) fabric_target=aarch64-linux-android ;;
    esac
    FABRIC_ANDROID_TARGET="$fabric_target" bash "$root/scripts/build-peer-android.sh" -p fabric-android --release
    mkdir -p "$root/.cache/jniLibs/$fabric_abi"
    cp "$root/target/$fabric_target/release/libfabric_android.so" "$root/.cache/jniLibs/$fabric_abi/"
done
# The repository wrapper matches the supplied ATAK 5.8 SDK template.
exec bash "$root/android/gradlew" -p "$root/android" :app:assembleCivDebug --console=plain "$@"
