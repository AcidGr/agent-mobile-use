#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Seam tests first. They cover the header line the Java tool writes and the JSON
# envelope this daemon builds around it — the one contract with no type system across
# languages, which has already produced two silent defects: a failure body (a single
# line, no rows) read as unparseable output, and a failure message double-quoted into
# `error="\"...\""` once %q re-quoted it.
echo "[build] Testing vd_server..."
go test ./...

echo "[build] Statically compiling vd_server (ARM64)..."
CGO_ENABLED=0 go build -ldflags="-s -w -extldflags '-static'" -o vd_server main.go
echo "[build] Done: vd_server binary ready."
ls -lh vd_server
