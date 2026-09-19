#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ANDROID_JAR="/usr/lib/android-sdk/platforms/android-23/android.jar"
DX="/usr/lib/android-sdk/build-tools/debian/dx"

mkdir -p classes bin

echo "[build] Compiling read-only accessibility probe..."
# -proc:none: the JDK ships an annotation processor path that ECJ cannot resolve
# here and would otherwise fail the build.
javac -proc:none -source 1.8 -target 1.8 -nowarn -cp "$ANDROID_JAR" \
    src/com/agent/ProbeMain.java \
    -d classes

echo "[build] Generating probe.dex..."
cd classes
"$DX" --dex --output=../bin/probe.dex com/agent/ProbeMain*.class

cd "$SCRIPT_DIR"
echo "[build] Build complete:"
ls -lh bin/probe.dex
