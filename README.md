# Agento Telegram Bot

Telegram bot for running OpenAI Codex CLI on a VPS as the `agento` user.
The project is built as a regular Spring Boot jar and deployed without Docker.

## Features

- accepts Telegram tasks and runs `codex exec` in `/home/agento`;
- prepends a VPS safety prompt before each Codex task;
- lets you change the model, reasoning effort, and access mode without redeploying;
- stores runtime settings in `agento-settings.properties` next to the jar;
- restricts access to one `TELEGRAM_ALLOWED_CHAT_ID`.

Plain text messages are treated as Codex tasks. `/codex text` is also supported as an explicit command.

## Telegram Commands

```text
/id
/ping
/status
/cancel
/model
/reasoning
/mode
/approval
/docker
/logs
/codex check docker ps and give a short report
```

Default models:

```text
gpt-5.5
gpt-5.4
gpt-5.4-mini
gpt-5.3-codex
gpt-5.2
```

Reasoning effort:

```text
low
medium
high
xhigh
```

`/mode` values:

```text
read-only
workspace
full-access
bypass
```

Default: `full-access`, which runs Codex with `--sandbox danger-full-access --ask-for-approval never`.
`bypass` uses `--dangerously-bypass-approvals-and-sandbox`.

## GitHub Actions Secrets

Required:

```text
VPS_HOST
VPS_SSH_KEY
TELEGRAM_BOT_TOKEN
TELEGRAM_ALLOWED_CHAT_ID
```

Usually also useful:

```text
VPS_USER=agento
VPS_PORT=22
VPS_APP_DIR=/home/agento
```

Optional Codex settings:

```text
CODEX_COMMAND=codex
CODEX_WORKDIR=/home/agento
CODEX_TIMEOUT_SECONDS=900
CODEX_MAX_OUTPUT_CHARS=30000
CODEX_MODEL=gpt-5.5
CODEX_REASONING_EFFORT=medium
CODEX_ACCESS_MODE=full-access
CODEX_APPROVAL_POLICY=never
CODEX_SETTINGS_FILE=./agento-settings.properties
CODEX_SYSTEM_PROMPT=...
```

## Local Run

```bash
cp .env.example .env
# Fill TELEGRAM_BOT_TOKEN and TELEGRAM_ALLOWED_CHAT_ID.
./scripts/run-local.sh
```

## VPS

Java 25 and Codex CLI must be installed on the VPS. Codex must already be authenticated for the `agento` user.
To install Codex through npm:

```bash
./scripts/install-codex-on-vps.sh
```

If Codex was installed through `nvm` and is not available in the PATH of a non-interactive SSH session, the workflow will try to find the binary inside `$HOME`.
