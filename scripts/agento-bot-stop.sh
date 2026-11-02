#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/agento/agento-bot}"
PID_FILE="$APP_DIR/app.pid"

if [ ! -f "$PID_FILE" ]; then
  echo "No PID file. Nothing to stop."
  exit 0
fi

PID=$(tr -d '[:space:]' < "$PID_FILE")
if [[ ! "$PID" =~ ^[1-9][0-9]*$ ]] || [ "$PID" -le 1 ]; then
  echo "Invalid PID in $PID_FILE; refusing to stop anything."
  exit 1
fi

if kill -0 "$PID" 2>/dev/null; then
  EXPECTED_DIR=$(cd "$APP_DIR" && pwd -P)
  ACTUAL_DIR=$(readlink -f "/proc/$PID/cwd" 2>/dev/null || true)
  PROCESS_ARGS="$(ps -p "$PID" -o args= || true) "
  if [ "$ACTUAL_DIR" != "$EXPECTED_DIR" ] || [[ "$PROCESS_ARGS" != *" -jar app.jar "* && "$PROCESS_ARGS" != *" -jar $EXPECTED_DIR/app.jar "* ]]; then
    echo "PID $PID does not match the bot runtime in $APP_DIR; refusing to stop it."
    exit 1
  fi

  kill "$PID"
  for attempt in {1..30}; do
    kill -0 "$PID" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$PID" 2>/dev/null; then
    kill -9 "$PID"
    sleep 1
  fi
  if kill -0 "$PID" 2>/dev/null; then
    echo "Bot process $PID did not stop."
    exit 1
  fi
fi

rm -f -- "$PID_FILE"
echo "Stopped."
