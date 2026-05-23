#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/agento}"
PID_FILE="$APP_DIR/app.pid"

if [ ! -f "$PID_FILE" ]; then
  echo "No PID file. Nothing to stop."
  exit 0
fi

PID=$(cat "$PID_FILE")
if ps -p "$PID" >/dev/null 2>&1; then
  kill "$PID"
  sleep 2
fi

if ps -p "$PID" >/dev/null 2>&1; then
  kill -9 "$PID"
fi

rm -f "$PID_FILE"
echo "Stopped."
