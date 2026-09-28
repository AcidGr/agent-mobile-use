#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ANDROID_JAR="/usr/lib/android-sdk/platforms/android-23/android.jar"
DX="/usr/lib/android-sdk/build-tools/debian/dx"

# Wipe intermediate classes before every build. javac only writes classes that still exist
# in the source, so stale inner classes from earlier revisions survive a clean-looking build
# and `dx com/agent/DaemonMain*.class` then sweeps them into the dex. That is exactly how a
# long-dead inner class (DaemonMain$1$1, the removed JPEG frame cache) kept shipping inside
# agent_vd.dex for months while being absent from the source. Always build from a clean slate.
rm -rf bin/classes
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

# Guard against the exact regression above: every class compiled into agent_vd.dex must have
# a matching source file. A stale orphan class would show up here as a .class with no .java.
echo "[build] Verifying every class in agent_vd.dex has a source counterpart..."
orphans=0
for cls in bin/classes/com/agent/*.class; do
    base="$(basename "$cls" .class)"   # DaemonMain$1$1.class -> DaemonMain$1$1
    top="${base%%\$*}"                  # DaemonMain$1$1 -> DaemonMain
    if [ ! -f "src/com/agent/${top}.java" ]; then
        echo "[build] ERROR: orphan class ${base}.class has no src/com/agent/${top}.java" >&2
        orphans=$((orphans+1))
    fi
done
if [ "$orphans" -ne 0 ]; then
    echo "[build] FAILED: ${orphans} orphan class(es) found; dex would contain code outside the source tree." >&2
    exit 1
fi

echo "[build] Build complete: bin/agent_tools.dex and bin/agent_vd.dex are ready."
ls -lh bin/agent_tools.dex bin/agent_vd.dex
