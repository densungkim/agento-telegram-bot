# PLAN — deploy the agent to the oracle-free server (branch `oracle-free`)

## Status

| # | Step | Status | Effort | Commit |
|---|------|--------|--------|--------|
| 1 | Prepare the oracle-free server | ✅ done | medium | — |
| 2 | Branch `oracle-free` with its own deploy workflow | ✅ done | medium | `425fb4a`; master `9152a4d` |
| 3 | README on `oracle-free`: independent branch | ▶ next | low | — |
| 4 | Secrets commands, deploy, check | ⏳ todo | low | — |

Legend: ✅ done · 🔄 in progress · ▶ next · ⏳ todo. Model: always Opus 5.5.

**Resume here:** Step 2 done (workflow `425fb4a`, master guard `9152a4d` not pushed). Next: Step 3, README section on
`oracle-free`.

### How to use this file
- Source of truth for progress. Re-read it at session start and after context compaction, before doing anything.
- Work on one step at a time. Tick each sub-task `[x]` immediately after finishing it and keep "Resume here" current.
- Step done = done criteria met (+ `mvn -B -q package -DskipTests` for code steps). Then update the table, set the next
  step to ▶, recommend the next step's effort in chat and stop.
- Commits, pushes and deploys only when the user allows or asks.
- Server rules: name the server and the commands before changing anything; never touch other users' services, containers,
  volumes or data on oracle-free (it already runs other bots and infrastructure).
- Forbidden without explicit permission: running the bot outside the deploy workflow, `claude -p` calls, destructive commands.

### Scope
- In scope: user `agento` and its runtime (Java, Claude Code, sandbox tools) on oracle-free; branch `oracle-free`, created
  from `master` once, that deploys the bot to oracle-free with `*_ORACLE_FREE` secrets and another Telegram bot.
- `master` (contabo) and `oracle-free` (oracle-free) are independent branches and are **never merged** in either direction.
  Later changes are made in the branch they belong to (a change wanted in both is ported by hand or `git cherry-pick`).
- Out of scope: new bot features; the contabo deployment; other services on oracle-free.
- This plan file lives on branch `oracle-free` (committed there, not on `master`).

### Servers
| | contabo (existing) | oracle-free (new) |
|---|---|---|
| Admin SSH | `contabo-root` (root) | `oracle-free` (`ubuntu`, passwordless sudo) |
| Bot user SSH | `contabo-agento` | `agento` (to create; key `C:\Users\densu\.ssh\vps_agento_ed25519`) |
| OS / arch | Ubuntu 24.04, x86_64 | Ubuntu 24.04.5, aarch64 |
| Java | 25 (sdkman) | none yet |
| Docker group | `agento` is a member | group exists (`ubuntu`, `runner`) |
| AppArmor userns restriction | `1` (profile for `bwrap` added) | `1` |
| Branch / workflow | `master` / `deploy.yml` | `oracle-free` / own workflow |

### Secrets for the branch (suffix `_ORACLE_FREE`)
Required: `VPS_HOST_ORACLE_FREE`, `VPS_SSH_KEY_ORACLE_FREE` (private key `vps_agento_ed25519`),
`TELEGRAM_BOT_TOKEN_ORACLE_FREE`, `TELEGRAM_ALLOWED_CHAT_ID_ORACLE_FREE`, `CLAUDE_CODE_OAUTH_TOKEN_ORACLE_FREE`
(the same subscription token may be reused). Optional ones keep the workflow defaults.

---

## Step 1 — Prepare the oracle-free server (medium)
- [x] User `agento` created (uid 1003, home `/home/agento`, bash), member of group `docker`.
- [x] `~agento/.ssh/authorized_keys` with `vps_agento_ed25519.pub` (700/600); key login `agento@<oracle-free host>` works.
      No local SSH alias added.
- [x] **Ask user first:** Java — the user installed sdkman + Temurin 25.0.5 for `agento` himself (sdkman of `ubuntu` is not
      visible to `agento`).
- [x] `java -version` as `agento` in a non-interactive SSH session (profile + sdkman init, like the workflow):
      Temurin 25.0.5+8, `/home/agento/.sdkman/candidates/java/current/bin/java`.
- [x] `bubblewrap` + `socat` installed, AppArmor profile `/etc/apparmor.d/bwrap` loaded; `bwrap` works as `agento`.
      `needrestart` deferred a `docker.service` restart — Docker was not restarted.
- [x] Claude Code 2.1.330 (native, arm64) in `~/.local/bin/claude` for `agento`.
- [x] As `agento`: `docker ps` works (14 running containers), `/home/agento` is writable.

**Done when:** `ssh` as `agento` works with the key, and Java 25, Claude Code, `docker` and `bwrap` work for `agento`.

## Step 2 — Branch `oracle-free` with its own deploy workflow (medium)
- [x] Branch `oracle-free` created from `master` at `c313aa3`; this plan committed there (`6eda826`).
- [x] Workflow on `oracle-free` (`.github/workflows/deploy.yml`, same file name): all 29 secret references have the
      `_ORACLE_FREE` suffix (env names inside the job unchanged), name "Build and deploy Agento Bot (oracle-free)",
      concurrency group `agento-bot-oracle-free-deploy`, `workflow_dispatch` only; error texts name the new secrets.
- [x] Guard: first step "Check branch" fails unless `github.ref_name == oracle-free`.
- [x] **Ask user first:** mirror guard in `master`'s workflow — user agreed; committed on `master` as `9152a4d`
      (not pushed yet, see User actions).
- [x] Syntax: `bash -n` on the guard, prepare and remote shell blocks; `mvn -B -q package -DskipTests` passes.

**Done when:** branch `oracle-free` exists and its workflow reads only `*_ORACLE_FREE` secrets and deploys only from itself.

## Step 3 — README on `oracle-free`: independent branch (low)
- [ ] README section on `oracle-free`: this branch deploys to oracle-free, is independent from `master` and never merged;
      deploy with "Run workflow" on branch `oracle-free`; the list of `*_ORACLE_FREE` secrets.

**Done when:** README on `oracle-free` describes the branch.

## Step 4 — Secrets commands, deploy, check (low)
- [ ] Exact `gh secret set …_ORACLE_FREE` commands → "User actions".
- [ ] After the user's go-ahead: push the branch, run the workflow on `oracle-free`, check the log and that the startup message
      arrives in the new bot's chat.
- [ ] Manual test checklist for the new bot (short version of PLAN-claude-migration Step 7).

**Done when:** the oracle-free bot runs from the branch and answers in its own chat.

---

## Decisions log
_Non-obvious decisions and deviations, newest last._

- 2026-11-22: `oracle-free` starts from `master`'s code; the branches are independent and never merged (user's decision).
  Plan changed accordingly: Step 3 no longer describes a merge-based sync, Step 2 adds a branch guard in the workflow, and
  this file is committed on `oracle-free`.
- 2026-11-22: the outbox path `/home/agento/agento-outbox` and the protected bot directory `/home/agento/agento-bot` are the
  same on oracle-free, because the bot user is also `agento` with home `/home/agento`.

## User actions (after all steps)
_`user, claude` = Claude can do it after the user's go-ahead; `only user` = needs the user._

| # | Action | Who | Claude can do it if | Status |
|---|--------|-----|---------------------|--------|
| 1 | Add the `*_ORACLE_FREE` secrets (commands from Step 4; tokens and chat id are your values) | only user | never for token values; my go-ahead for `VPS_HOST` | ⏳ |
| 2 | Push branch `oracle-free` and run its workflow | user, claude | my go-ahead | ⏳ |
| 2a | Push `master` (guard commit `9152a4d`) | user, claude | my go-ahead | ⏳ |
| 3 | Manual test of the new bot in Telegram | only user | never (manual testing on my device) | ⏳ |
