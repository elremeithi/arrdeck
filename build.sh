#!/usr/bin/env bash
# Builds ArrDeck without Gradle: aapt -> javac -> dx -> zipalign -> apksigner
set -euo pipefail
cd "$(dirname "$0")"
SDK_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"
aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$SDK_JAR" -F "$OUT/app.unsigned.apk"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$SDK_JAR" \
  -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && aapt add app.unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"
KS=${KEYSTORE:-arrdeck.keystore}
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass arrdeck -keypass arrdeck -alias arrdeck \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ArrDeck" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:arrdeck --key-pass pass:arrdeck --out ArrDeck.apk "$OUT/app.aligned.apk"
apksigner verify ArrDeck.apk && ls -la ArrDeck.apk
