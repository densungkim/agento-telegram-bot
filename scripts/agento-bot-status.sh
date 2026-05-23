#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/agento}"
PID_FILE="$APP_DIR/app.pid"
LOG_FILE="$APP_DIR/app.log"

if [ -f "$PID_FILE" ]; then
  PID=$(cat "$PID_FILE")
  if ps -p "$PID" >/dev/null 2>&1; then
    echo "RUNNING pid=$PID"
  else
    echo "PID file exists, but process is not running: $PID"
  fi
else
  echo "No PID file: $PID_FILE"
fi

echo "--- last logs ---"
if [ -f "$LOG_FILE" ]; then
  tail -n 80 "$LOG_FILE"
else
  echo "No log file: $LOG_FILE"
fi
