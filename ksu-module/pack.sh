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

# 3. Create zip
cd "$SCRIPT_DIR"
echo "[ksu-pack] Building agent-mobile-use-ksu.zip..."
rm -f "$WORKSPACE/agent-mobile-use-ksu.zip"
zip -r "$WORKSPACE/agent-mobile-use-ksu.zip" . -x "*.git*" -x "pack.sh"

# 4. Copy to Download folder for easy flashing
cp "$WORKSPACE/agent-mobile-use-ksu.zip" /storage/emulated/0/Download/agent-mobile-use-ksu.zip

echo "[ksu-pack] Package ready:"
ls -lh "$WORKSPACE/agent-mobile-use-ksu.zip" /storage/emulated/0/Download/agent-mobile-use-ksu.zip
