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
- continues one Codex session across messages and bot restarts;
- removes the bot's previous session on `/new`, without keeping a second transcript;
- restricts access to one `TELEGRAM_ALLOWED_CHAT_ID`.

Plain text messages are treated as Codex tasks. Slash commands are reserved for the bot UI.

## Telegram Commands

```text
/ping
/status
/cancel
/new
/model
/reasoning
/mode
check docker ps and give a short report
```

## Conversation Context

The first task runs `codex exec --json`. The bot records the UUID from
`thread.started` immediately, using an atomic replacement of
`CODEX_SESSION_FILE` (default: `./agento-session.properties`). Later messages use
`codex exec resume <UUID>`; the bot never uses `--last` or silently switches to
a new session when resuming fails. Stdout JSON events and stderr diagnostics are
read separately. No copy of the messages or execution events is stored by the bot.

The state file contains the session UUID, an ownership marker, the working
directory, the effective `CODEX_HOME`, and whether a reset is pending. It is bound
to that workdir and Codex home so a changed deployment configuration cannot reset
a session in another CLI installation. Keep the same OS user, workdir, Codex home,
and state-file path across restarts and deployments. The CLI's existing auth and
configuration stay in that same Codex home; the bot does not copy them.
Run one bot process per state file.

`/new` removes the recorded session through `codex delete <UUID> --force`. Codex
also deletes its spawned descendant sessions and associated session metadata.
After successful deletion, the bot clears the UUID; the next text message creates
a fresh conversation. Model, reasoning, and access settings are preserved.
Unrelated CLI sessions, server files, and Telegram messages are left in place.
Shared CLI caches and logs are not wiped. See
[Codex session commands](https://learn.chatgpt.com/docs/developer-commands?surface=cli#codex-delete)
and [session deletion semantics](https://learn.chatgpt.com/docs/app-server#threads).

If deletion fails, times out, or is cancelled, the old UUID and reset marker stay
available. New tasks are blocked until `/new` succeeds, including after a restart.
An idle bot can retry `/new`; it does not discard a failed cleanup and start
accumulating new sessions. `/status` shows the current session and pending reset.
The CLI's `thread not found` error for the exact recorded UUID is treated as an
already completed deletion, so a reset can recover after a crash between deleting
the CLI session and clearing the bot's state file.

`/new` is refused while a task or cancellation is in progress. `/cancel` requests
process termination and keeps the bot busy until the worker finishes. Termination
also targets the subprocesses visible under the CLI process; it cannot undo
commands that have already changed the VPS or control detached services.

Unreadable, invalid, or differently bound state files fail startup. An error saving
a newly observed UUID stops execution and blocks further tasks until `/new`; an
abrupt OS/process kill before the UUID is saved can still leave an untracked CLI
session. Keep the state file and Codex session storage together for recovery.
Existing one-shot sessions created before this change are not imported or deleted.

## Models and Reasoning

Model and reasoning options come from `src/main/resources/codex-models.json`,
based on the bundled catalog of Codex CLI 0.166.0. This is a compatibility snapshot,
not a live list of models enabled for your account.

| Model | Explicit reasoning levels | Catalog default |
| --- | --- | --- |
| `gpt-6.1-sol`, `gpt-6.1-terra`, `gpt-6-astra`, `gpt-5.6-sol` | low, medium, high, xhigh, max, ultra | low |
| `gpt-6-sol`, `gpt-5.6-terra` | low, medium, high, xhigh, max, ultra | medium |
| `gpt-6-luna`, `gpt-5.6-luna` | low, medium, high, xhigh, max | medium |
| `gpt-5.5` (API-key catalog only) | low, medium, high, xhigh | medium |

Every model also has a `default` reasoning option. For an explicit model, the bot
passes that model's catalog default to Codex. With `/model default`, both model
and reasoning overrides are omitted: your CLI configuration and its own defaults
apply. Only `/reasoning default` is offered in this mode, since the bot does not
know which model the CLI will resolve.

`CODEX_AUTH_MODE=chatgpt` is the default catalog profile. Set it to `api-key` when
the CLI uses an API key. This setting only filters the bot's menu; it does not
change or verify authentication. GPT-5.5 retired from ChatGPT-authenticated Codex
on October 14, 2026, but remains available with API-key authentication. GPT-5.4,
GPT-5.4-mini, and GPT-5.3-Codex are no longer offered in the bundled bot menu.
See the [official model availability guidance](https://learn.chatgpt.com/docs/models).

The reasoning menu depends on the selected model. Changing a model preserves
compatible reasoning; an incompatible value is reset to `default` and shown in
the settings summary. `none` and `minimal` are not advertised for the current
bundled models; a replacement catalog can include them for a compatible model.
Ultra enables automatic task delegation and can use more time and account quota.

Saved model and reasoning choices are never silently migrated. If a stored choice
is no longer supported by the active catalog or filters, `/status` explains how
to select a replacement. Tasks are blocked until the settings are corrected.
The bot does not automatically retry a task with another model after a CLI error.

For a different provider or newer model metadata, set `CODEX_MODEL_CATALOG_FILE`
to an existing JSON file on the VPS using the same structure as the bundled file,
then restart the bot. The file replaces the entire catalog. Update it from
metadata matching your CLI version and provider; listing a model does not grant
account access. Hidden specialist and auto-review models are omitted by default.

`CODEX_ALLOWED_MODELS` optionally restricts the catalog to listed IDs (include
`default` explicitly if you want the CLI-configured model option). An empty value
exposes the whole catalog profile. A filter with no matching entries fails at
startup. `CODEX_ALLOWED_REASONING_EFFORTS` optionally restricts explicit effort
choices within each model's supported set; the `default` choice remains available.

Runtime choices in `agento-settings.properties` take precedence over environment
defaults. Settings are saved through an atomic file replacement before being
applied in memory. Unreadable settings fail startup rather than silently reverting.

When upgrading an existing installation, review old `CODEX_MODEL`,
`CODEX_REASONING_EFFORT`, and `CODEX_ALLOWED_MODELS` environment values or GitHub
Actions secrets. Replace obsolete model filters or clear them, set the matching
catalog authentication profile, then select a model explicitly in Telegram if
the saved choice needs replacement. Choosing `/model default` also resets an
explicit reasoning override to `default`.

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
CODEX_MODEL=default
CODEX_REASONING_EFFORT=default
CODEX_AUTH_MODE=chatgpt
CODEX_MODEL_CATALOG_FILE=
CODEX_ALLOWED_MODELS=
CODEX_ALLOWED_REASONING_EFFORTS=
CODEX_ALLOWED_ACCESS_MODES=read-only,workspace,full-access
CODEX_ACCESS_MODE=full-access
CODEX_SETTINGS_FILE=./agento-settings.properties
CODEX_SESSION_FILE=./agento-session.properties
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

Deployment preserves `agento-session.properties`, `agento-settings.properties`,
and the CLI's session storage. A custom `CODEX_SESSION_FILE` can be supplied as a
GitHub Actions secret; its parent directory must be writable by the bot user.

If Codex was installed through `nvm` and is not available in the PATH of a non-interactive SSH session, the workflow will try to find the binary inside `$HOME`.
