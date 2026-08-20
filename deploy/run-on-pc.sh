#!/usr/bin/env bash
# Runs the ShotArc server on this machine, storing data under the repo so it survives restarts.
# Reads secrets from deploy/shotarc.env if present.
set -euo pipefail
cd "$(dirname "$0")/../server"
[ -d node_modules ] || npm install --omit=dev --no-audit --no-fund
export DATA_DIR="${DATA_DIR:-$PWD/data}"
export APK_PATH="${APK_PATH:-$DATA_DIR/golf-tracker.apk}"
[ -f ../deploy/shotarc.env ] && set -a && . ../deploy/shotarc.env && set +a
# Bind all interfaces so Windows/LAN can reach it; a tunnel or firewall decides who actually can.
HOST=0.0.0.0 PORT="${PORT:-8080}" node -e '
  process.env.BIND="0.0.0.0"; import("./server.js")
' 2>/dev/null || HOST=0.0.0.0 node server.js
