#!/usr/bin/env bash
# Stop the demo instance. Never touches the real daemon's PID file.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT_DIR/portfolio-bench-demo.pid"

if [ ! -f "$PID_FILE" ]; then
  echo "No demo PID file — nothing to stop."
  exit 0
fi

PID="$(cat "$PID_FILE")"
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  echo "Stopped demo (PID $PID)."
else
  echo "Demo PID $PID is not running — clearing stale PID file."
fi
rm -f "$PID_FILE"
