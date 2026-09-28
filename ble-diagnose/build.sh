#!/usr/bin/env bash
# Baut die BLE-Diagnose-APK ohne Android SDK / Gradle.
# Alle Werkzeuge kommen von Maven Central:
#   aapt2 + Framework-Ressourcen  -> org.apktool:apktool-lib
#   Android-API zum Kompilieren   -> org.robolectric:android-all
#   dx (Dex-Compiler)             -> com.jakewharton.android.repackaged:dalvik-dx
#   apksig (Signatur v1+v2)       -> com.android.tools.build:apksig
# Benoetigt: JDK 17+, python3, curl, unzip, keytool.
set -euo pipefail

cd "$(dirname "$0")"
TOOLS="${TOOLS:-$HOME/.cache/ble-diagnose-tools}"
M=https://repo1.maven.org/maven2
mkdir -p "$TOOLS"

fetch() {
  local path="$1" file="$TOOLS/$(basename "$1")"
  if [ ! -s "$file" ]; then
    echo "Lade $(basename "$path")"
    for i in 1 2 3 4; do curl -sSfL -o "$file" "$M/$path" && return 0; sleep $((i * 4)); done
    echo "Download fehlgeschlagen: $path" >&2; exit 1
  fi
}

fetch org/apktool/apktool-lib/3.0.3/apktool-lib-3.0.3.jar
fetch org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
fetch com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar
fetch com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar

if [ ! -x "$TOOLS/prebuilt/linux/aapt2" ]; then
  unzip -o -q "$TOOLS/apktool-lib-3.0.3.jar" 'prebuilt/linux/aapt2' 'prebuilt/android-framework.jar' -d "$TOOLS"
  chmod +x "$TOOLS/prebuilt/linux/aapt2"
fi
AAPT2="$TOOLS/prebuilt/linux/aapt2"
FRAMEWORK="$TOOLS/prebuilt/android-framework.jar"
ANDROID_JAR="$TOOLS/android-all-14-robolectric-10818077.jar"

KEYSTORE="$TOOLS/debug.p12"
if [ ! -s "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass android -keypass android \
    -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=BLE-Diagnose Debug" >/dev/null
fi

B=build
rm -rf "$B" && mkdir -p "$B/gen" "$B/classes"

"$AAPT2" compile --dir res -o "$B/res.zip"
"$AAPT2" link -o "$B/base.apk" -I "$FRAMEWORK" --manifest AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 1 --version-name 1.0 \
  --java "$B/gen" "$B/res.zip"

javac -nowarn --release 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java') 2>&1 | grep -v -E '^(warning|Note|1 warning)' || true
[ -f "$B/classes/de/redmagiccooler/blediagnose/MainActivity.class" ] || { echo "javac fehlgeschlagen" >&2; exit 1; }

java -cp "$TOOLS/dalvik-dx-16.0.1.jar" com.android.dx.command.Main --dex --min-sdk-version=24 \
  --output="$B/classes.dex" "$B/classes"

python3 tools/package_apk.py "$B/base.apk" "$B/classes.dex" "$B/unsigned.apk"
# apksig 2.3.0 initialisiert beim Laden JDK-Interna, daher --add-exports.
java --add-exports java.base/sun.security.x509=ALL-UNNAMED \
  -cp "$TOOLS/apksig-2.3.0.jar" tools/Sign.java "$KEYSTORE" android debug "$B/unsigned.apk" "$B/ble-diagnose.apk"

echo "Fertig: $(pwd)/$B/ble-diagnose.apk"
