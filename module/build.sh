#!/usr/bin/env bash
set -e
X=/home/USER/android-re/work/xmod
AJ=/home/USER/android-re/sdk/platforms/android-35/android.jar
BT=/home/USER/android-re/sdk/build-tools/37.0.0
KEYS=/home/USER/android-re/re-keys/release.jks
cd "$X"
rm -rf build
mkdir -p build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$AJ" --manifest AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 35 -A assets build/res.zip
javac -source 8 -target 8 -encoding UTF-8 -cp "$AJ:api-82.jar" -d build/classes $(find src -name '*.java')
"$BT/d8" --lib "$AJ" --min-api 26 --output build/dex $(find build/classes -name '*.class')
cd build/dex && zip -q ../base.apk classes.dex && cd "$X"
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYS" --ks-pass pass:android --key-pass pass:android --ks-key-alias re --out build/module-probe.apk build/aligned.apk
"$BT/apksigner" verify --print-certs build/module-probe.apk | head -3
ls -lh build/module-probe.apk
