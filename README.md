# Agento Telegram Bot

Telegram bot for running OpenAI Codex CLI on a VPS as the `agento` user.
The project is built as a regular Spring Boot jar and deployed without Docker.

## Runtime and Development Requirements

- Java 25 LTS, with current security updates, for development and the VPS runtime;
- Maven 3.9.16 for development and CI;
- Spring Boot 4.1.2, which manages the Spring, Jackson, and Maven plugin versions;
- Codex CLI 0.166.0 or newer (stable releases only), authenticated as the bot's OS user;
- Node.js 22+ and npm when using the npm-based Codex installer.

The bot uses Spring's blocking REST client and the JDK HTTP client for Telegram.
It does not need an embedded web server. Java's compilation target remains 25.

The Codex installer pins the version from `scripts/codex-version.txt` instead of
installing the moving npm `latest` tag. This file also sets the minimum CLI version
checked by deployment. To update this baseline, change the file, review the CLI
release notes, and update the VPS installation deliberately.

Version references:
[Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html),
[Codex CLI release notes](https://learn.chatgpt.com/docs/changelog).

## Features

- accepts Telegram tasks and runs `codex exec` in `/home/agento`;
- deploys bot runtime files into `/home/agento/agento-bot`;
- prepends a VPS safety prompt before each Codex task;
- lets you change the model, reasoning effort, and access mode without redeploying;
- stores runtime settings in `agento-settings.properties` next to the jar;
- restricts access to one `TELEGRAM_ALLOWED_CHAT_ID`.

Plain text messages are treated as Codex tasks. Slash commands are reserved for the bot UI.

## Telegram Commands

```text
/ping
/status
/cancel
/model
/reasoning
/mode
check docker ps and give a short report
```

Default models:

```text
gpt-5.5
gpt-5.4
gpt-5.4-mini
gpt-5.3-codex
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
```

Default: `full-access`, which runs Codex with `--dangerously-bypass-approvals-and-sandbox`.
Send the task as plain text when you want Codex to execute it.

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
VPS_APP_DIR=/home/agento/agento-bot
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

Java 25 LTS and a supported Codex CLI version must be installed on the VPS.
Codex must already be authenticated for the `agento` user.
Run the installer from the updated repository as that same user to install the pinned Codex version:

```bash
./scripts/install-codex-on-vps.sh
```

Deployment checks Java and Codex versions before stopping the existing bot.
It does not install or upgrade Java or Codex automatically. A successful version
check does not verify Codex authentication or model availability.

If Codex was installed through `nvm` and is not available in the PATH of a non-interactive SSH session, the workflow will try to find the binary inside `$HOME`.
