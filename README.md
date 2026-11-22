# Agento Telegram Bot

Telegram bot for running the Claude Code CLI on a VPS as the `agento` user.
The project is built as a regular Spring Boot jar and deployed without Docker.

## Runtime and Development Requirements

- Java 25 LTS, with current security updates, for development and the VPS runtime;
- Maven 3.9.16 for development and CI;
- Spring Boot 4.1.2, which manages the Spring, Jackson, and Maven plugin versions;
- Claude Code CLI (native installer), at least the version in `scripts/claude-version.txt`,
  authenticated for the bot's OS user with a Claude subscription token;
- `bubblewrap` and `socat` on the VPS for the `workspace` access mode (Bash sandbox).

The bot uses Spring's blocking REST client and the JDK HTTP client for Telegram.
It does not need an embedded web server.

Version references:
[Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html),
[Claude Code CLI reference](https://code.claude.com/docs/en/cli-reference).

## Features

- accepts Telegram tasks and runs `claude -p` in `CLAUDE_WORKDIR` (default `/home/agento`);
- deploys bot runtime files into `/home/agento/agento-bot`;
- appends a VPS safety prompt to Claude Code's system prompt (`--append-system-prompt`);
- lets you change the model, effort, and access mode without redeploying;
- stores runtime settings in `agento-settings.properties` next to the jar;
- continues one Claude Code session across messages and bot restarts;
- removes the bot's previous session on `/new`;
- restricts access to one `TELEGRAM_ALLOWED_CHAT_ID`.

Plain text messages are treated as Claude tasks. Slash commands are reserved for the bot UI.

## Telegram Interface

`/start` opens the inline control panel. `/menu` (also `/settings`) opens the current
settings. Bot controls and command descriptions are in Russian; CLI diagnostics retain
their original language. `/help` explains conversation context and cancellation.

Model, effort, and access choices use inline buttons with a selected-value indicator
and pagination. Outdated choice menus are rejected. Explicit commands such as
`/model sonnet` still work. Commands addressed to this bot with `@username` are
recognized; unknown slash commands are handled locally and never become Claude tasks.
Attachments are not passed to the agent; this version accepts text tasks only.

Each accepted task receives a short ID and a progress message linked to its source
message. A background scheduler updates that message about every five seconds. It
shows elapsed time, a generic stage derived from the tool Claude is using (commands,
files, search, subagent…), and the settings captured when the task was accepted.
Tool arguments, output, and thinking text are not copied into progress messages.
The cancel button targets its own task ID; an old button cannot cancel a later task.

Responses reply to the original request when it is still available. Short answers
support fenced code blocks, inline code, and bold text using escaped Telegram HTML;
a formatting rejection falls back to plain text. Answers exceeding
`TELEGRAM_MESSAGE_MAX_LENGTH` (default 3900, clamped to 500–3900) are sent in full as
a UTF-8 `.md` attachment (upload guard 49 MB). When Claude Code denied actions because
of the access mode, the answer ends with the denied tool names.

`CLAUDE_MAX_OUTPUT_CHARS` limits diagnostic output, not the final answer. Progress
edits back off according to Telegram's `retry_after`. Result delivery failures are
reported on the progress card; neither the task nor the delivery is replayed.

## Telegram Commands

```text
/start
/menu
/help
/ping
/status
/cancel
/new
/model
/effort
/mode
check docker ps and give a short report
```

## How a Task Runs

```text
claude -p --output-format stream-json --verbose
       [--model M] [--effort E]
       --permission-mode <mode> --permission-prompts none
       [--settings <sandbox json>]            # workspace mode only
       [--append-system-prompt <CLAUDE_SYSTEM_PROMPT>]
       --session-id <new UUID> | --resume <UUID>
```

The task text is written to stdin. Stdout is read as JSON events: `system/init`
confirms the session, `assistant` tool calls drive the progress stage, and the
`result` event (read until the end of the stream) carries the answer, errors, and
permission denials. Stderr is kept as diagnostics. `ANTHROPIC_API_KEY` and
`CLAUDE_CODE_EFFORT_LEVEL` are removed from the child environment so they cannot
override the subscription token or the chosen effort; `TELEGRAM_BOT_TOKEN` is removed
so the commands Claude runs cannot read it; `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB` is
removed because it forces the permission mode to `default`. `system/init` reports the
resolved model id and the permission mode Claude Code really started with; the
progress card and `/status` show them (`**Запущено:** claude-opus-5-5 · bypassPermissions`)
and flag a downgraded mode. There is no turn or budget
limit; `CLAUDE_TIMEOUT_SECONDS` (default 900) stops a task.

## Conversation Context

The bot keeps one session. Before the first task it generates a UUID, writes it to
`CLAUDE_SESSION_FILE` (default `./agento-session.properties`) with an atomic file
replacement, and starts Claude Code with `--session-id <UUID>`. When Claude Code
reports the same id in `system/init`, the session is marked confirmed; later tasks
use `--resume <UUID>`. A different id stops the task. The bot stores no copy of the
messages; Claude Code keeps the transcript in
`<CLAUDE_CONFIG_DIR or ~/.claude>/projects/<encoded workdir>/<UUID>.jsonl`.

The state file contains the UUID, an ownership marker, the working directory, the
Claude Code config directory, and whether the session is confirmed. A state file
bound to another workdir or config directory fails startup. Keep the same OS user,
workdir, `CLAUDE_CONFIG_DIR`, and state-file path across deployments, and run one bot
process per state file.

Claude Code deletes old sessions (`cleanupPeriodDays`, 30 days by default). Resuming a
deleted session fails with `No conversation found`; the bot shows the error with a
hint to use `/new`.

The bot has a single user, so a session replaced by `/new` is never needed again.
`/new` first saves an empty session id, so the next message always starts a new
conversation, then deletes everything Claude Code keeps for the old session:
`projects/*/<UUID>.jsonl` (and its orphaned / superseded copies), `projects/*/<UUID>/`
(subagents, tool results), `file-history`, `session-env`, `tasks`, `uploads`, `dev-mods`,
`image-cache`, and `debug` entries for that UUID, and the project's auto memory
(`projects/<encoded workdir>/memory/`). Tasks run with `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`,
so a new session never sees notes from an old one. Ids whose files could not be
deleted are kept as `pendingDeletion` in the state file and retried on the next `/new`
and at bot startup; `/status` shows how many are left. Model, effort, and access settings are preserved. `/new` is refused
while a task or cancellation is in progress. `/cancel` stops the CLI process and its
subprocesses; it cannot undo commands that already changed the VPS.

## Models and Effort

The menus offer the values from `CLAUDE_ALLOWED_MODELS` (default
`default,opus,sonnet,haiku,fable`) and `CLAUDE_ALLOWED_EFFORTS` (default
`default,low,medium,high,xhigh,max`). Models may be aliases or full model names
accepted by `claude --model`. `default` means the flag is not passed, so Claude Code's
own configuration applies. Effort is independent of the model; when a model does not
support the chosen level, Claude Code falls back to the closest lower one. Invalid
list entries fail startup.

Runtime choices in `agento-settings.properties` take precedence over the environment
defaults `CLAUDE_MODEL`, `CLAUDE_EFFORT`, and `CLAUDE_ACCESS_MODE`. Settings are saved
through an atomic file replacement. A saved value missing from the allowed lists is
reported in `/status`, and tasks are blocked until another value is selected.

## Access Modes

| `/mode` | Claude Code flags | What runs |
| --- | --- | --- |
| `read-only` | `--permission-mode default` | reads and read-only shell commands; everything else is denied |
| `workspace` | `--permission-mode acceptEdits` + Bash sandbox | file edits inside the workdir; shell commands in the sandbox (writes only in the workdir, no network) |
| `full-access` | `--permission-mode bypassPermissions` | everything, without permission checks |

The default is `full-access`. Sandbox settings: `enabled`, `autoAllowBashIfSandboxed`,
`allowUnsandboxedCommands: false`, `failIfUnavailable: true` (a missing `bubblewrap` /
`socat` fails the task instead of running unsandboxed). Reads outside the workdir are
not blocked in `workspace` mode. `bypassPermissions` is refused by Claude Code for root,
so the bot must run as a regular user. `CLAUDE_ALLOWED_ACCESS_MODES` limits the menu.

## GitHub Actions Secrets

Required:

```text
VPS_HOST
VPS_SSH_KEY
TELEGRAM_BOT_TOKEN
TELEGRAM_ALLOWED_CHAT_ID
CLAUDE_CODE_OAUTH_TOKEN      # from `claude setup-token`, valid for one year
```

Usually also useful:

```text
VPS_USER=agento
VPS_PORT=22
VPS_APP_DIR=/home/agento/agento-bot
TELEGRAM_MESSAGE_MAX_LENGTH=3900
```

Optional Claude settings:

```text
CLAUDE_COMMAND=claude
CLAUDE_WORKDIR=/home/agento
CLAUDE_TIMEOUT_SECONDS=900
CLAUDE_MAX_OUTPUT_CHARS=30000
CLAUDE_MODEL=default
CLAUDE_EFFORT=default
CLAUDE_ACCESS_MODE=full-access
CLAUDE_ALLOWED_MODELS=default,opus,sonnet,haiku,fable
CLAUDE_ALLOWED_EFFORTS=default,low,medium,high,xhigh,max
CLAUDE_ALLOWED_ACCESS_MODES=read-only,workspace,full-access
CLAUDE_SETTINGS_FILE=./agento-settings.properties
CLAUDE_SESSION_FILE=./agento-session.properties
CLAUDE_SYSTEM_PROMPT=...
CLAUDE_CONFIG_DIR=           # only if Claude Code should not use ~/.claude
```

The deployment also sets `DISABLE_AUTOUPDATER=1`. Do not set
`CLAUDE_CODE_SUBPROCESS_ENV_SCRUB`: Claude Code then forces the permission mode to
`default`, and full access silently stops working. Never set `ANTHROPIC_API_KEY`
for the bot: in `-p` mode it always wins over the subscription token.

## Local Run

```bash
cp .env.example .env
# Fill TELEGRAM_BOT_TOKEN, TELEGRAM_ALLOWED_CHAT_ID and CLAUDE_CODE_OAUTH_TOKEN.
./scripts/run-local.sh
```

## VPS

Java 25 LTS and Claude Code must be installed for the `agento` user. Install Claude Code
with the native installer (no sudo), pinned to the version in `scripts/claude-version.txt`:

```bash
./scripts/install-claude-on-vps.sh
```

Updates are manual (`claude update`), because the bot runs with `DISABLE_AUTOUPDATER=1`.
For the `workspace` mode, install `bubblewrap` and `socat` (`sudo apt-get install
bubblewrap socat`); on Ubuntu with restricted unprivileged user namespaces, `bwrap`
also needs an AppArmor profile (see the Claude Code sandboxing docs).

Before stopping the existing bot, deployment checks: not running as root, Java 25+,
Claude Code at least `scripts/claude-version.txt` (`~/.local/bin/claude` is found even
when it is not in the non-interactive PATH), `claude auth status` with the token from
`app.env`, and the working directory. Missing `bwrap` / `socat` only prints a warning
when `workspace` is an allowed mode. `app.env` holds the tokens and gets mode 600. It
does not install or upgrade Java or Claude Code. The workflow builds the jar with `-Dmaven.test.skip=true`. Deployments are
serialized. An existing bot gets up to 30 seconds to stop before forced termination;
a PID is stopped only when its working directory and jar command match the expected
runtime. The new process must confirm its first successful Telegram `getUpdates`
within 150 seconds, otherwise the workflow fails and prints the startup log. There is
no automatic rollback after replacing the jar.

Deployment preserves `agento-session.properties`, `agento-settings.properties`, and the
Claude Code session storage.
