#!/usr/bin/env bash
# Builds Leve.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs a JDK (17+) and the Android SDK (build-tools 34+, platform android-34).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"

rm -rf out && mkdir -p out/gen out/classes
"$BT/aapt2" compile --dir app/res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest app/AndroidManifest.xml --java out/gen out/res.zip \
  --min-sdk-version 26 --target-sdk-version 34
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -encoding UTF-8 -d out/classes $(find app/src out/gen -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$AJ" --output out $(find out/classes -name '*.class')
(cd out && zip -qj base.apk classes.dex)
"$BT/zipalign" -f -p 4 out/base.apk out/aligned.apk

# Own key, created once: updates need the same one, so keep leve.keystore.
if [ ! -f leve.keystore ]; then
  keytool -genkeypair -keystore leve.keystore -alias leve -keyalg RSA -keysize 2048 -validity 20000 \
    -storepass leveleve -keypass leveleve -dname "CN=Leve" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks leve.keystore --ks-pass pass:leveleve --key-pass pass:leveleve --out Leve.apk out/aligned.apk
echo "Built Leve.apk ($(du -h Leve.apk | cut -f1)). Install: adb install -r Leve.apk"
