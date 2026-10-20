#!/usr/bin/env bash
# Phase 0 fallback build path for sandboxes where dl.google.com is blocked
# (so Gradle's google() repo can never resolve the Android Gradle Plugin,
# aapt2-from-Maven, or SDK platforms via sdkmanager). Builds the debug APK
# by driving aapt2/d8/apksigner directly, using only:
#   - apt packages from the normal Ubuntu archive (aapt2, aidl, zipalign,
#     apksigner, the API 23 android.jar) — see the
#     `apt-get install android-sdk-build-tools android-sdk-platform-23` step
#   - r8.jar (bundles d8) from storage.googleapis.com, which is reachable
#     even when dl.google.com is not
#   - kotlinc from the Ubuntu `kotlin` package
# This path is capped at API 23 (Marshmallow) — fine for proving the
# pipeline, not sufficient for the real app (needs API 26+ for
# AccessibilityService / notification channels). Real feature builds
# should run wherever the full Android SDK is reachable (a PC, Android
# Studio, or CI such as GitHub Actions).
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK=/usr/lib/android-sdk
BT="$SDK/build-tools/29.0.3"
ANDROID_JAR="$SDK/platforms/android-23/android.jar"
CACHE="$PROJ/.buildtools-cache"
OUT="$PROJ/.manualbuild"
PKG_PATH="com/family/motherassistant"
APP_ID="com.family.motherassistant"

mkdir -p "$CACHE"
if [ ! -f "$CACHE/r8.jar" ]; then
  echo "== fetching r8.jar (d8) from storage.googleapis.com =="
  curl -sSL --max-time 120 -o "$CACHE/r8.jar" \
    "https://storage.googleapis.com/r8-releases/raw/8.2.42/r8.jar"
fi

rm -rf "$OUT"
mkdir -p "$OUT"/{gen,classes,dex,apk,res-compiled}

echo "== aapt2 compile resources =="
"$BT/aapt2" compile --dir "$PROJ/app/src/main/res" -o "$OUT/res-compiled"

echo "== aapt2 link (manifest + resources -> base.apk, generates R.java) =="
"$BT/aapt2" link \
  -o "$OUT/apk/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  "$OUT/res-compiled"/*.flat

echo "== javac: compile generated R.java =="
javac -cp "$ANDROID_JAR" -d "$OUT/classes" "$OUT/gen/$PKG_PATH/R.java"

echo "== kotlinc: compile app sources =="
kotlinc -cp "$ANDROID_JAR:$OUT/classes" -d "$OUT/classes" \
  $(find "$PROJ/app/src/main/java" -name "*.kt")

echo "== d8: dex =="
java -cp "$CACHE/r8.jar" com.android.tools.r8.D8 \
  --output "$OUT/dex" \
  --lib "$ANDROID_JAR" \
  $(find "$OUT/classes" -name "*.class")

echo "== package + align + sign =="
cp "$OUT/apk/base.apk" "$OUT/apk/unsigned.apk"
(cd "$OUT/dex" && zip -j "$OUT/apk/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"

if [ ! -f "$OUT/debug.keystore" ]; then
  keytool -genkeypair -v -keystore "$OUT/debug.keystore" -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

FINAL_APK="$OUT/apk/${APP_ID}-debug.apk"
"$BT/apksigner" sign --ks "$OUT/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$FINAL_APK" "$OUT/apk/aligned.apk"

echo "== verify =="
"$BT/apksigner" verify -v "$FINAL_APK"

echo
echo "Debug APK: $FINAL_APK"
