#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -n "${FABRIC_NDK_BIN:-}" ]]; then
    fabric_ndk_bin=$FABRIC_NDK_BIN
else
    : "${ANDROID_HOME:?Set ANDROID_HOME or FABRIC_NDK_BIN to locate the Android NDK}"
    fabric_ndk_bin="$ANDROID_HOME/ndk/${FABRIC_NDK_VERSION:-27.1.12297006}/toolchains/llvm/prebuilt/linux-x86_64/bin"
fi
fabric_android_target="${FABRIC_ANDROID_TARGET:-x86_64-linux-android}"
case "$fabric_android_target" in
    x86_64-linux-android|aarch64-linux-android) ;;
    *) echo "Unsupported Android target: $fabric_android_target" >&2; exit 2 ;;
esac
fabric_target_key="${fabric_android_target//-/_}"
export "CARGO_TARGET_${fabric_target_key^^}_LINKER=$fabric_ndk_bin/${fabric_android_target}26-clang"
export "CC_$fabric_target_key=$fabric_ndk_bin/${fabric_android_target}26-clang"
export "AR_$fabric_target_key=$fabric_ndk_bin/llvm-ar"
# NDK r27 needs explicit ELF alignment for Android devices with 16 KB pages.
fabric_flags_key="CARGO_TARGET_${fabric_target_key^^}_RUSTFLAGS"
export "$fabric_flags_key=${!fabric_flags_key:-} -C link-arg=-Wl,-z,max-page-size=16384"
export CARGO_PROFILE_DEV_DEBUG=0
cd -- "$root"
if [ "$#" -eq 0 ]; then set -- -p fabric-peer-link-experiment; fi
exec cargo +1.98.0 build --locked --target "$fabric_android_target" "$@"
