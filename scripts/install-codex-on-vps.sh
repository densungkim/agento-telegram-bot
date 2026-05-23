#!/usr/bin/env bash
set -euo pipefail

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js is not installed. Install Node.js 22+ or use nvm first."
  echo "Example: nvm install --lts"
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "npm is not installed."
  exit 1
fi

npm install -g @openai/codex
codex --version
