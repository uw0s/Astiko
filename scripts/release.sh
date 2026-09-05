#!/usr/bin/env bash
# Build a signed release APK + AAB into dist/.
# Usage: scripts/release.sh [--no-bump] [versionName]
# --no-bump ships the current versionCode as-is (initial 1.0 release only).
#
# The release MUST be signed, so the script refuses to run
# before touching anything if credentials are absent.
# Signing credentials live in ~/.gradle/gradle.properties:
#   ASTIKO_KEYSTORE_FILE / ASTIKO_KEYSTORE_PASSWORD /
#   ASTIKO_KEY_ALIAS / ASTIKO_KEY_PASSWORD
set -euo pipefail
cd "$(dirname "$0")/.."

# Version literals live in app/build.gradle.kts
BUILD_FILE="app/build.gradle.kts"
GRADLE_PROPS="${HOME}/.gradle/gradle.properties"

# ---- Signing: fail closed BEFORE any state changes -------------------------
# The gradle build resolves these via project.findProperty from the user
# gradle.properties. Checking here mirrors exactly what the build will see.
keystore_file=$(sed -n 's/^ASTIKO_KEYSTORE_FILE=//p' "$GRADLE_PROPS" 2>/dev/null || true)
keystore_password=$(sed -n 's/^ASTIKO_KEYSTORE_PASSWORD=//p' "$GRADLE_PROPS" 2>/dev/null || true)
key_alias=$(sed -n 's/^ASTIKO_KEY_ALIAS=//p' "$GRADLE_PROPS" 2>/dev/null || true)
key_password=$(sed -n 's/^ASTIKO_KEY_PASSWORD=//p' "$GRADLE_PROPS" 2>/dev/null || true)

missing=0
[ -n "$keystore_file" ] || { echo "error: ASTIKO_KEYSTORE_FILE is not set in $GRADLE_PROPS" >&2; missing=1; }
[ -n "$keystore_password" ] || { echo "error: ASTIKO_KEYSTORE_PASSWORD is not set in $GRADLE_PROPS" >&2; missing=1; }
[ -n "$key_alias" ] || { echo "error: ASTIKO_KEY_ALIAS is not set in $GRADLE_PROPS" >&2; missing=1; }
[ -n "$key_password" ] || { echo "error: ASTIKO_KEY_PASSWORD is not set in $GRADLE_PROPS" >&2; missing=1; }

# Expand a leading `~/` and resolve relative paths against
# the project root (the same resolution Gradle's file() applies)
# so the existence check is meaningful.
if [ -n "$keystore_file" ]; then
  case "$keystore_file" in
    \~/*) keystore_file="$HOME/${keystore_file#\~/}" ;;
  esac
  case "$keystore_file" in
    /*) ;;
    *) keystore_file="$PWD/$keystore_file" ;;
  esac
  if [ ! -f "$keystore_file" ]; then
    echo "error: keystore not found at $keystore_file" >&2
    missing=1
  fi
fi

if [ "$missing" -ne 0 ]; then
  echo "error: release signing is not fully configured. Refusing to build an unsigned release." >&2
  exit 1
fi

# ---- Version bump (restored on any failure) --------------------------------
code=$(sed -n 's/^[[:space:]]*versionCode = \([0-9]*\).*/\1/p' "$BUILD_FILE")
name=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' "$BUILD_FILE")
[ -n "$code" ] || { echo "error: versionCode literal missing in $BUILD_FILE" >&2; exit 1; }

# Parse args: --no-bump ships the literal code, everything else is the
# versionName. Bumping is mandatory after the initial release because Play
# silently skips same-code updates, so --no-bump is refused unless the tree
# still holds the untouched first version (versionCode 1).
no_bump=0
name_arg=""
for arg in "$@"; do
    if [ "$arg" = "--no-bump" ]; then
        no_bump=1
    else
        name_arg="$arg"
    fi
done
if [ "$no_bump" -eq 1 ] && [ "$code" -ne 1 ]; then
    echo "error: --no-bump is only for the initial 1.0 release (versionCode is $code, expected 1)" >&2
    exit 1
fi

if [ "$no_bump" -eq 1 ]; then
    new_code=$code
else
    new_code=$((code + 1))
fi
new_name="${name_arg:-$name}"

restore() {
  sed -i.bak -E "s/^([[:space:]]*versionCode = )[0-9]+/\\1$code/" "$BUILD_FILE" && rm -f "$BUILD_FILE.bak"
  sed -i.bak -E "s/^([[:space:]]*versionName = \")[^\"]*(\")/\\1$name\\2/" "$BUILD_FILE" && rm -f "$BUILD_FILE.bak"
}
trap restore ERR

sed -i.bak -E "s/^([[:space:]]*versionCode = )[0-9]+/\\1$new_code/" "$BUILD_FILE" && rm -f "$BUILD_FILE.bak"
sed -i.bak -E "s/^([[:space:]]*versionName = \")[^\"]*(\")/\\1$new_name\\2/" "$BUILD_FILE" && rm -f "$BUILD_FILE.bak"
echo "==> Releasing $new_name (versionCode $new_code)"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  echo "==> Using Android Studio JBR as JAVA_HOME"
fi

# The literals in the build file are tracked config-cache inputs, so the
# bump above invalidates the cache automatically, and no -P flags are
# needed.
./gradlew :app:assembleRelease :app:bundleRelease

mkdir -p dist
apk="app/build/outputs/apk/release/app-release.apk"
aab="app/build/outputs/bundle/release/app-release.aab"
mapping="app/build/outputs/mapping/release/mapping.txt"
apk_out="dist/astiko-$new_name-$new_code.apk"
aab_out="dist/astiko-$new_name-$new_code.aab"

[ -f "$apk" ] || { echo "error: $apk missing after assembleRelease" >&2; exit 1; }
[ -f "$aab" ] || { echo "error: $aab missing after bundleRelease" >&2; exit 1; }
cp "$apk" "$apk_out"
cp "$aab" "$aab_out"

# ---- Verify the signature ---------------------------------------------------
# apksigner lives in the SDK's build-tools; pick the
# highest installed version. Signature verification
# is the fail-closed counterpart of the credential check.
sdk_dir=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || true)
if [ -z "$sdk_dir" ] || [ ! -d "$sdk_dir" ]; then
  sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
fi
apksigner=""
for candidate in "$sdk_dir"/build-tools/*/apksigner; do
  [ -x "$candidate" ] && apksigner="$candidate" # last (highest) version wins
done
if [ -z "$apksigner" ]; then
  echo "error: apksigner not found under $sdk_dir/build-tools. Install Android SDK build-tools" >&2
  exit 1
fi
echo ""
echo "==> Verifying APK signature"
"$apksigner" verify --verbose --print-certs "$apk_out"

# ---- Validate the AAB (bundletool/jarsigner) --------------------------------
# bundletool validate is the standard check, but it's not always installed;
# jarsigner still proves the bundle carries the release key's JAR signature
# (Play requires JAR signing on AABs. The self-signed-cert warnings are
# normal for Android signing and don't affect verification).
if command -v bundletool >/dev/null 2>&1; then
  echo "==> Validating AAB (bundletool)"
  bundletool validate --bundle "$aab_out"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jarsigner" ]; then
  echo "==> bundletool not on PATH, verifying the AAB's JAR signature with jarsigner"
  if "$JAVA_HOME/bin/jarsigner" -verify "$aab_out" >/dev/null 2>&1; then
    echo "    AAB signature: verified (JAR scheme)"
  else
    echo "error: AAB signature verification failed" >&2
    exit 1
  fi
else
  echo "==> bundletool not on PATH and no jarsigner, skipping AAB validation (https://github.com/google/bundletool)"
fi

# ---- Checksums + retained R8 mapping ----------------------------------------
shasum -a 256 "$apk_out" "$aab_out" > "dist/astiko-$new_name-$new_code.sha256"
echo ""
echo "==> SHA-256 checksums (dist/astiko-$new_name-$new_code.sha256):"
cat "dist/astiko-$new_name-$new_code.sha256"
if [ -f "$mapping" ]; then
  cp "$mapping" "dist/astiko-$new_name-$new_code-mapping.txt"
  echo "==> R8 mapping: dist/astiko-$new_name-$new_code-mapping.txt"
else
  echo "warning: no R8 mapping at $mapping, is minification enabled?" >&2
fi

echo ""
echo "==> Release artifacts in dist/:"
echo "    AAB (Play Console):                  $aab_out"
echo "    APK (Firebase/sideload/F-Droid):     $apk_out"
