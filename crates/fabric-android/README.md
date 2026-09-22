# Android JNI bridge

`fabric-android` links Arachne Core into `libfabric_android.so`. The Kotlin ATAK
plugin calls it through `FabricNative`; native code owns the fabric session and
protocol work.

Build the Android libraries and plugin with the root
[`scripts/build-plugin.sh`](../../scripts/build-plugin.sh) after setting the
SDK paths described in the [root README](../../README.md). The root
`Cargo.lock` pins Rust dependencies.
