#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKSPACE="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "[ksu-pack] Refreshing compiled binaries..."

# 1. Copy Go vd_server
cp "$WORKSPACE/vd-server-go/vd_server" "$SCRIPT_DIR/bin/vd_server"

# 2. Copy Java tools dex
cp "$WORKSPACE/vd-tool-java/bin/agent_tools.dex" "$SCRIPT_DIR/bin/agent_tools.dex"
cp "$WORKSPACE/vd-tool-java/bin/agent_vd.dex" "$SCRIPT_DIR/bin/agent_vd.dex"

# 3. Copy Hook APK
cp "$WORKSPACE/agent-hook-apk/build/agent_hook.apk" "$SCRIPT_DIR/apk/agent_hook.apk"

# 4. Create zip
cd "$SCRIPT_DIR"
VERSION=$(grep "^version=" module.prop | cut -d= -f2)
echo "[ksu-pack] Building agent-mobile-use-ksu-v${VERSION}.zip..."
rm -f "$WORKSPACE/agent-mobile-use-ksu.zip"
zip -r "$WORKSPACE/agent-mobile-use-ksu.zip" . -x "*.git*" -x "pack.sh"

mkdir -p "$WORKSPACE/release"
cp "$WORKSPACE/agent-mobile-use-ksu.zip" "$WORKSPACE/release/agent-mobile-use-ksu-v${VERSION}.zip"

# 5. Copy to Download folder for easy flashing
cp "$WORKSPACE/agent-mobile-use-ksu.zip" /storage/emulated/0/Download/agent-mobile-use-ksu.zip 2>/dev/null || true

echo "[ksu-pack] Package ready:"
ls -lh "$WORKSPACE/release/agent-mobile-use-ksu-v${VERSION}.zip"
