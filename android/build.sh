#!/usr/bin/env bash
# Builds DubStudio.apk without Gradle.
# Needs JDK 17+, Android build-tools 34 and platform android-34.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-$HOME/Android/Sdk}"
BT="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | head -1)}"
PLAT="${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}"
KS="${KEYSTORE:-$PWD/dubstudio.keystore}"
OUT="$PWD/build"

rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/src"

echo "== resources =="
"$BT/aapt2" compile --dir app/res -o "$OUT/res/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLAT" \
  --manifest app/AndroidManifest.xml -A app/assets \
  --java "$OUT/gen" --auto-add-overlay "$OUT/res/res.zip"

echo "== javac =="
mkdir -p "$OUT/src/com/rajok/dubstudio"
cp app/java/com/rajok/dubstudio/*.java "$OUT/src/com/rajok/dubstudio/"
if ! javac -nowarn -source 8 -target 8 -bootclasspath "$PLAT" -encoding UTF-8 \
  -d "$OUT/classes" $(find "$OUT/gen" "$OUT/src" -name '*.java') 2>&1 \
  | grep -v -e 'bootstrap class path' -e 'Picked up'; then :; fi
if [ ! -f "$OUT/classes/com/rajok/dubstudio/MainActivity.class" ] || \
   [ ! -f "$OUT/classes/com/rajok/dubstudio/Sarvam.class" ] || \
   [ ! -f "$OUT/classes/com/rajok/dubstudio/DubbingEngine.class" ] || \
   [ ! -f "$OUT/classes/com/rajok/dubstudio/Audio.class" ]; then
  echo "COMPILE FAILED"; exit 1
fi

echo "== dex =="
"$BT/d8" --lib "$PLAT" --min-api 26 --release --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "== package =="
cd "$OUT" && cp base.apk unsigned.apk && (cd dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 unsigned.apk aligned.apk

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass dubstudio -keypass dubstudio \
    -alias dub -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=DubStudio, O=Rajok, C=IN" >/dev/null 2>&1
fi

echo "== sign =="
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:dubstudio --key-pass pass:dubstudio \
  --ks-key-alias dub --out "$OLDPWD/DubStudio.apk" aligned.apk
"$BT/apksigner" verify --print-certs "$OLDPWD/DubStudio.apk" | head -3
echo "built: $OLDPWD/DubStudio.apk"
