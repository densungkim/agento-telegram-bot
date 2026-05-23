# Agento Telegram Bot

Telegram-бот для запуска OpenAI Codex CLI на VPS от имени пользователя `agento`.
Проект собирается в обычный Spring Boot jar и разворачивается без Docker.

## Что делает бот

- принимает задачи из Telegram и запускает `codex exec` в `/home/agento`;
- передает Codex системное вступление о том, что он работает на реальном VPS;
- позволяет менять модель, reasoning effort и режим доступа без redeploy;
- сохраняет runtime-настройки в `agento-settings.properties` рядом с jar;
- ограничивает доступ одним `TELEGRAM_ALLOWED_CHAT_ID`.

Обычное текстовое сообщение считается задачей для Codex. Команда `/codex текст` оставлена как явный вариант.

## Команды Telegram

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
/codex проверь docker ps и дай короткий отчет
```

Модели по умолчанию:

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

Режимы `/mode`:

```text
read-only
workspace
full-access
bypass
```

Default: `full-access`, что запускает Codex с `--sandbox danger-full-access --ask-for-approval never`.
`bypass` использует `--dangerously-bypass-approvals-and-sandbox`.

## GitHub Actions secrets

Обязательные:

```text
VPS_HOST
VPS_SSH_KEY
TELEGRAM_BOT_TOKEN
TELEGRAM_ALLOWED_CHAT_ID
```

Обычно также нужны:

```text
OPENAI_API_KEY
VPS_USER=agento
VPS_PORT=22
VPS_APP_DIR=/home/agento
```

Опциональные Codex-настройки:

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

## Локальный запуск

```bash
cp .env.example .env
# заполнить TELEGRAM_BOT_TOKEN и TELEGRAM_ALLOWED_CHAT_ID
./scripts/run-local.sh
```

## VPS

На VPS должен быть установлен Java 25 и Codex CLI. Для установки Codex через npm:

```bash
./scripts/install-codex-on-vps.sh
```

Если Codex установлен через `nvm` и не попадает в PATH non-interactive SSH-сессии, workflow попробует найти бинарник внутри `$HOME`.
