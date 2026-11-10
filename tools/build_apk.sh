#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# HandWrite — reproducible APK build (no Gradle required).
#
#   ./tools/build_apk.sh [--release]
#
# The script compiles the Kotlin sources against android.jar, dexes them with d8,
# links resources with aapt2, aligns and signs the APK with a *stable* keystore so
# future versions can be installed over the old ones.
#
# Environment (all optional, sane defaults below):
#   JAVA_HOME   JDK 17 with javac
#   KOTLINC     path to the Kotlin compiler (kotlinc script)
#   SDK         Android SDK root (build-tools/34.0.0 + platforms/android-34)
#   KEYSTORE    keystore file (default: keystore/handwrite-release.jks)
#   KS_ALIAS, KS_PASS
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JAVA_HOME="${JAVA_HOME:-/home/user/tools/jdk-17.0.20.1+1}"
KOTLINC="${KOTLINC:-/home/user/tools/kotlinc/bin/kotlinc}"
SDK="${SDK:-/home/user/tools/android-sdk}"
BUILD_TOOLS="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
KEYSTORE="${KEYSTORE:-$ROOT/keystore/handwrite-release.jks}"
KS_ALIAS="${KS_ALIAS:-handwrite}"
KS_PASS="${KS_PASS:-handwrite2026}"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

OUT="$ROOT/build/apk"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
RESCOMP="$OUT/res"
rm -rf "$OUT"
mkdir -p "$CLASSES" "$DEX" "$RESCOMP" "$OUT/gen"

VERSION_NAME="0.0.2"
VERSION_CODE="2"
APP_ID="com.handwrite.app"

echo "==> 1/7 compile Kotlin"
SRC_DIRS=("app/src/main/kotlin")
find app/src/main/kotlin -name '*.kt' | sort > "$OUT/sources.txt"
"$KOTLINC" $(cat "$OUT/sources.txt") \
  -classpath "$PLATFORM" \
  -d "$CLASSES" \
  -nowarn -jvm-target 1.8 2>&1 | grep -v "^warning:" || true
cp "$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar" "$OUT/kotlin-stdlib.jar"
if [ ! -d "$CLASSES/com" ]; then
  echo "!! compilation failed"
  exit 1
fi

echo "==> 2/7 dex (d8, min-api 23)"
find "$CLASSES" -name '*.class' > "$OUT/classes.txt"
"$BUILD_TOOLS/d8" --min-api 23 --lib "$PLATFORM" \
  --output "$DEX" $(cat "$OUT/classes.txt") "$OUT/kotlin-stdlib.jar" 2>&1 | grep -v "Warning: Unexpected error during rewriting of Kotlin metadata" | grep -v "^\sat " | grep -v "^com.android" || true

echo "==> 3/7 resources (aapt2)"
"$BUILD_TOOLS/aapt2" compile --dir app/src/main/res -o "$RESCOMP/res.zip"
MANIFEST="$OUT/gen/AndroidManifest.xml"
sed -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" \
    -e "s/android:versionCode=\"[^\"]*\"/android:versionCode=\"$VERSION_CODE\"/" \
    app/src/main/AndroidManifest.xml > "$MANIFEST"
"$BUILD_TOOLS/aapt2" link -o "$OUT/base.apk" \
  -I "$PLATFORM" --manifest "$MANIFEST" \
  --min-sdk-version 23 --target-sdk-version 34 \
  --java "$OUT/gen" \
  -A app/src/main/assets \
  "$RESCOMP/res.zip"

echo "==> 4/7 package assets + dex"
cd "$OUT"
cp base.apk unaligned.apk
(cd "$DEX" && zip -q -X "$OUT/unaligned.apk" classes*.dex)
cd "$ROOT"

echo "==> 5/7 zipalign"
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "==> 6/7 sign"
if [ ! -f "$KEYSTORE" ]; then
  echo "!! keystore not found: $KEYSTORE"
  exit 1
fi
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/handwrite-$VERSION_NAME.apk" "$OUT/aligned.apk"

echo "==> 7/7 verify"
"$BUILD_TOOLS/apksigner" verify --print-certs "$OUT/handwrite-$VERSION_NAME.apk" | head -6
"$BUILD_TOOLS/aapt2" dump badging "$OUT/handwrite-$VERSION_NAME.apk" | head -3
ls -la "$OUT/handwrite-$VERSION_NAME.apk"
