#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "[build] Statically compiling vd_server (ARM64)..."
CGO_ENABLED=0 go build -ldflags="-s -w -extldflags '-static'" -o vd_server main.go
echo "[build] Done: vd_server binary ready."
ls -lh vd_server
