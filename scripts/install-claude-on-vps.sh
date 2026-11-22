#!/usr/bin/env bash
# Installs the pinned Claude Code version with the native installer for the current user
# (~/.local/bin/claude). Run as the bot user, not as root.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CLAUDE_VERSION="$(tr -d '\r\n' < "$SCRIPT_DIR/claude-version.txt")"
if [[ ! "$CLAUDE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Invalid Claude Code version in $SCRIPT_DIR/claude-version.txt"
  exit 1
fi

if [ "$(id -u)" -eq 0 ]; then
  echo "Run this script as the bot user, not as root."
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is not installed."
  exit 1
fi

curl -fsSL https://claude.ai/install.sh | bash -s "$CLAUDE_VERSION"
"$HOME/.local/bin/claude" --version
echo "Claude Code updates are manual for the bot (DISABLE_AUTOUPDATER=1): run 'claude update' deliberately."
