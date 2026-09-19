#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ANDROID_JAR="/usr/lib/android-sdk/platforms/android-23/android.jar"
XPOSED_JAR="/root/xposed_lib/xposed-api-stub.jar"
DX="/usr/lib/android-sdk/build-tools/debian/dx"
AAPT="/usr/bin/aapt"
APKSIGNER="/usr/bin/apksigner"

rm -rf build
mkdir -p build/gen build/classes build/apk

echo "[build] 1. Generating R.java and initial package with aapt..."
"$AAPT" package -f -m \
    -S res \
    -J build/gen \
    -M AndroidManifest.xml \
    -I "$ANDROID_JAR" \
    -F build/apk/unaligned.apk

echo "[build] 2. Compiling Java sources..."
javac -proc:none -source 1.8 -target 1.8 \
    -cp "$ANDROID_JAR:$XPOSED_JAR" \
    $(find src build/gen -name "*.java") \
    -d build/classes

echo "[build] 3. Converting classes to classes.dex..."
cd build/classes
"$DX" --dex --output=../classes.dex $(find . -name "*.class")
cd "$SCRIPT_DIR"

echo "[build] 4. Adding classes.dex and assets to APK..."
cd build
"$AAPT" add "apk/unaligned.apk" "classes.dex"
cd "$SCRIPT_DIR"
"$AAPT" add "build/apk/unaligned.apk" "assets/xposed_init"

echo "[build] 5. Signing APK with debug key..."
if [ ! -f "/root/debug.keystore" ]; then
    keytool -genkey -v -keystore /root/debug.keystore \
        -storepass android -alias androiddebugkey -keypass android \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

"$APKSIGNER" sign --ks /root/debug.keystore \
    --ks-pass pass:android \
    --ks-key-alias androiddebugkey \
    --key-pass pass:android \
    --out "build/agent_hook.apk" \
    "build/apk/unaligned.apk"

echo "[build] Build successful: build/agent_hook.apk"
ls -lh build/agent_hook.apk
