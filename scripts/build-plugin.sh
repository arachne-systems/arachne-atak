#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export FABRIC_TAK_SDK="${FABRIC_TAK_SDK:-$root/.cache/sdk/ATAK-CIV-5.8.0.4-SDK}"
export ANDROID_HOME="${ANDROID_HOME:-/home/user/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
: "${FABRIC_TAK_SDK:?Provide the extracted TAK SDK path}"
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
