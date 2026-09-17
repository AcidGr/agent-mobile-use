#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ANDROID_JAR="/usr/lib/android-sdk/platforms/android-23/android.jar"
DX="/usr/lib/android-sdk/build-tools/debian/dx"

mkdir -p bin/classes

echo "[build] Compiling Java source files..."
javac -proc:none -source 1.8 -target 1.8 -cp "$ANDROID_JAR" \
    src/com/agent/ToolMain.java \
    src/com/agent/DaemonMain.java \
    -d bin/classes

echo "[build] Generating agent_tools.dex..."
cd bin/classes
"$DX" --dex --output=../agent_tools.dex com/agent/ToolMain*.class

echo "[build] Generating agent_vd.dex..."
"$DX" --dex --output=../agent_vd.dex com/agent/DaemonMain*.class

cd "$SCRIPT_DIR"
echo "[build] Build complete: bin/agent_tools.dex and bin/agent_vd.dex are ready."
ls -lh bin/agent_tools.dex bin/agent_vd.dex
