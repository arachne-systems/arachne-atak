#!/usr/bin/env bash
# One low-priority Gradle lane per worktree. Native libraries are an explicit input.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
: "${FABRIC_TAK_SDK:?Set FABRIC_TAK_SDK to an extracted ATAK SDK}"
lane="${GRADLE_LANE:-default}"
[[ "$lane" =~ ^[a-zA-Z0-9_-]+$ ]] || { echo "Invalid GRADLE_LANE" >&2; exit 2; }
exec flock "/tmp/arachne-atak-gradle-$lane.lock" nice -n 19 \
  bash "$root/android/gradlew" -p "$root/android" --console=plain \
  --project-cache-dir "$root/.cache/gradle-$lane" "$@"
