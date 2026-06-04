#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
JAR="$ROOT_DIR/target/portfolio-bench-1.0-SNAPSHOT.jar"
PID_FILE="$ROOT_DIR/portfolio-bench.pid"
LOG_DIR="$HOME/log"
LOG_FILE="$LOG_DIR/portfolio-bench.log"

if [ ! -f "$JAR" ]; then
    echo "Jar not found at $JAR" >&2
    echo "Build it first: mvn package -DskipTests" >&2
    exit 1
fi

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "Already running (PID $(cat "$PID_FILE"))" >&2
    exit 1
fi

mkdir -p "$LOG_DIR"

# Archive the previous log with a timestamp so the latest is always portfolio-bench.log.
if [ -f "$LOG_FILE" ]; then
    TS=$(date +%Y-%m-%d_%H-%M-%S)
    mv "$LOG_FILE" "$LOG_DIR/portfolio-bench.$TS.log"
fi

java -jar "$JAR" </dev/null >"$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"

echo "Started portfolio-bench (PID $(cat "$PID_FILE"))"
echo "  Web UI: http://127.0.0.1:8080"
echo "  Log:    $LOG_FILE"
echo "  Stop:   scripts/stop.sh  (or: kill $(cat "$PID_FILE"))"
