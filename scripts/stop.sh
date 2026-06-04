#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
PID_FILE="$ROOT_DIR/portfolio-bench.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "No PID file at $PID_FILE" >&2
    exit 1
fi

PID=$(cat "$PID_FILE")
if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    echo "Sent SIGTERM to PID $PID"
else
    echo "PID $PID not running; cleaning up stale PID file" >&2
fi
rm -f "$PID_FILE"
