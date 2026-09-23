# ATAK TPP release process

Use this for each Arachne ATAK release candidate. TPP receives the clean source
ZIP; the local APK and AAB are build checks, not TPP-signed release artifacts.

## 1. Prepare a candidate

Start from the intended, clean release commit and confirm the `core` submodule
is at its recorded commit. Increase `versionCode` for each installable build
that supersedes a prior one, and update `versionName` in
`android/app/build.gradle.kts`. The ATAK SDK patch and plugin API version are
separate: use SDK 5.8.0.5 with `-PatakVersion=5.8.0`, which sets
`com.atakmap.app@5.8.0.CIV` in the plugin manifest.

Build the local candidate with the exact SDK selected:

```bash
export FABRIC_TAK_SDK=/home/user/.cache/sdk/ATAK-CIV-5.8.0.5-SDK
export ANDROID_HOME=/home/user/Android/Sdk
export ANDROID_SDK_ROOT=/home/user/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./scripts/build-plugin.sh :app:assembleCivRelease :app:bundleCivRelease -PatakVersion=5.8.0
```

## 2. Make and check the TPP source ZIP

Build the native libraries first so `.cache/jniLibs` contains both ABIs. From
the clean source root, run:

```bash
set -euo pipefail
test -z "$(git status --porcelain)"
test -z "$(git -C core status --porcelain)"
source_commit=$(git rev-parse HEAD)
core_commit=$(git -C core rev-parse HEAD)
staging=$(mktemp -d)
mkdir -p "$staging/Arachne" "$staging/source"
git archive "$source_commit" android | tar -xf - -C "$staging/source"
cp -a "$staging/source/android/." "$staging/Arachne/"
mkdir -p "$staging/Arachne/native-source/core"
git archive "$source_commit" .gitattributes .gitignore .gitmodules Cargo.lock Cargo.toml LICENSE NOTICE.md README.md crates scripts/build-peer-android.sh | tar -xf - -C "$staging/Arachne/native-source"
git -C core archive "$core_commit" | tar -xf - -C "$staging/Arachne/native-source/core"
cp -a .cache/jniLibs "$staging/Arachne/app/src/main/"
printf '\nfabricJniLibs=src/main/jniLibs\n' >> "$staging/Arachne/gradle.properties"
cat > "$staging/Arachne/TPP-SOURCE.txt" <<EOF
Source commit: $source_commit
Core submodule commit: $core_commit
Version: 0.0.3-alpha (6)
ATAK SDK: 5.8.0.5
Plugin API: com.atakmap.app@5.8.0.CIV
EOF
version=0.0.3-alpha
archive="Arachne-$version-${source_commit:0:12}-tak-source.zip"
output=/mnt/c/Users/User/Downloads
(cd "$staging" && zip -q -r -X "$output/$archive" Arachne)
(cd "$output" && sha256sum "$archive" > "$archive.sha256")
unzip -t "$output/$archive"
unzip -Z1 "$output/$archive"
(cd "$output" && sha256sum -c "$archive.sha256")
if unzip -Z1 "$output/$archive" | rg -q '(^|/)(\.git|\.cache|\.gradle|build|target)(/|$)|(^|/)(local\.properties|android_keystore)$|\.(apk|aab)$'; then
  echo 'Unexpected build, private or signed artifact in TPP source ZIP' >&2
  exit 1
fi
```

The archive contains the Android project at its root, the pinned Rust source,
and the two built JNI libraries. `git archive` excludes ignored build products,
SDKs and keystores; the ZIP must not contain `.git`, `.cache`, `.gradle`,
`build`, `target`, `local.properties`, APKs or AABs. Extract it to a fresh
directory and run the release Gradle tasks there with the selected SDK before
uploading; this checks it builds without the original checkout's caches:

```bash
verify_zip="/mnt/c/Users/User/Downloads/Arachne-0.0.3-alpha-$(git rev-parse --short=12 HEAD)-tak-source.zip"
test -f "$verify_zip"
verify_dir=$(mktemp -d)
unzip -q "$verify_zip" -d "$verify_dir"
(
  cd "$verify_dir/Arachne"
  FABRIC_TAK_SDK=/home/user/.cache/sdk/ATAK-CIV-5.8.0.5-SDK \
  ANDROID_HOME=/home/user/Android/Sdk \
  ANDROID_SDK_ROOT=/home/user/Android/Sdk \
  JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  bash ./gradlew :app:assembleCivRelease :app:bundleCivRelease \
    -PatakVersion=5.8.0 --console=plain
)
```

This verifies local buildability only; TPP runs its own checks and signing.

## 3. TPP and GitHub draft

Upload only the source ZIP through the authorized TPP account and preserve its
checksum. Do not upload the locally signed APK as the release artifact. Keep a
GitHub release as a draft while TPP signing is pending; attach the source ZIP
and checksum, and state that the signed APK is pending. After TPP returns the
APK, verify its package/version, plugin API, signature and native libraries,
then add that returned APK to the draft. Publish only after the signed output
and the release checks are accepted.

Keep prior published releases intact. A new candidate needs a new version and
tag; point its tag at the exact source commit used for the ZIP. Push that
candidate commit to a release branch before creating the GitHub draft, and do
not publish the draft while TPP signing is pending. For this candidate:

```bash
archive="/mnt/c/Users/User/Downloads/Arachne-0.0.3-alpha-$(git rev-parse --short=12 HEAD)-tak-source.zip"
test -f "$archive"
git push origin HEAD:refs/heads/release/v0.0.3-alpha
gh release create v0.0.3-alpha "$archive" "$archive.sha256" \
  --draft --prerelease --target "$(git rev-parse HEAD)" \
  --title 'Arachne ATAK v0.0.3-alpha (TPP signing pending)' \
  --notes 'TPP signing pending. This draft contains the source ZIP and checksum; the returned TPP-signed APK will be added after verification.'
```
