#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CODEX_VERSION="$(tr -d '\r\n' < "$SCRIPT_DIR/codex-version.txt")"
if [[ ! "$CODEX_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Invalid Codex version in $SCRIPT_DIR/codex-version.txt"
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js is not installed. Install Node.js 22+ or use nvm first."
  echo "Example: nvm install --lts"
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "npm is not installed."
  exit 1
fi

npm install -g "@openai/codex@$CODEX_VERSION"
codex --version
