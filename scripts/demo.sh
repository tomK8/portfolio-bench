#!/usr/bin/env bash
# Start the demo instance: the same jar, pointed at generated data in demo/.
#
# Runs alongside the real daemon on its own port, PID file and log, so start.sh's
# single-instance guard is untouched and you can leave the real app running.
#
# The three directory flags and --portfolio.demo are passed on the COMMAND LINE on purpose:
# command-line arguments outrank the optional ~/.portfolio-bench.properties imported by
# application.properties, so nothing in the home config can point this at the real data or
# switch the demo banner off. Mail is force-disabled for the same reason.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT_DIR/target/portfolio-bench-1.0-SNAPSHOT.jar"
PID_FILE="$ROOT_DIR/portfolio-bench-demo.pid"
PORT="${DEMO_PORT:-8081}"
LOG_DIR="$HOME/log"
LOG_FILE="$LOG_DIR/portfolio-bench-demo.log"

if [ ! -f "$JAR" ]; then
  echo "No jar at $JAR — run 'mvn package -DskipTests' first." >&2
  exit 1
fi

if [ ! -f "$ROOT_DIR/demo/db/portfolio.db" ]; then
  echo "No demo database. Build it first:" >&2
  echo "  python3 scripts/make-demo-data.py" >&2
  exit 1
fi

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "Demo already running (PID $(cat "$PID_FILE")) on port $PORT."
  exit 0
fi

mkdir -p "$LOG_DIR"
if [ -f "$LOG_FILE" ]; then
  mv "$LOG_FILE" "$LOG_DIR/portfolio-bench-demo.$(date +%Y%m%d-%H%M%S).log"
fi

java -jar "$JAR" \
  --portfolio.demo=true \
  --server.port="$PORT" \
  --portfolio.db-dir="$ROOT_DIR/demo/db" \
  --portfolio.input-dir="$ROOT_DIR/demo/input" \
  --portfolio.output-dir="$ROOT_DIR/demo/output" \
  --portfolio.alert.mail.enabled=false \
  </dev/null >"$LOG_FILE" 2>&1 &

echo $! > "$PID_FILE"
echo "Demo started (PID $!) on http://127.0.0.1:$PORT — log: $LOG_FILE"
