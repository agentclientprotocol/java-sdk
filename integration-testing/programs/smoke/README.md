# Real-agent smoke

The Java client from this checkout (`StdioAcpClientTransport`) against the ACP agents people
actually run, spawned over stdio from their published npm packages. It catches Java client
breakage that the SDK-to-SDK suite cannot: real capability sets, real auth errors, update kinds the
SDK does not model yet.

It is optional and never part of the default run: `run-all.sh` and the nightly `cross-sdk.yml`
skip it. It runs locally with `run-smoke.sh` and in CI through `.github/workflows/cross-sdk-smoke.yml`
(manual dispatch only).

## What it checks

| Step | Runs | PASS means |
|---|---|---|
| `initialize` | always | a `protocolVersion` comes back; prints the agent's `agentInfo`, `agentCapabilities` and `authMethods` |
| `session/new` | always | a session id, or, **without credentials only**, the auth-required error `-32000` |
| `prompt` | with credentials | one short prompt ("Reply with exactly one word: pong") ends with a stop reason after at least one `session/update`; prints the update kinds, the reply and the reported cost |
| `close` | always | `closeGracefully` returns within 15 s and no agent process is left |

Each step has a hard timeout, and a watchdog kills the agent's whole process tree if the run
exceeds `SMOKE_TOTAL_TIMEOUT` (300 s). Output follows the suite's format: `STEP <name> PASS|FAIL|SKIP`
and a `RESULT` line. A `SKIP` (no credentials) is not a failure.

The client never calls `authenticate`. On these agents it persists credentials into the user's
configuration (Gemini CLI clears its cached Google login, codex-acp writes `~/.codex/auth.json`).
Agents read API keys from their environment instead.

## Agents

| Agent | Package (pinned in `smoke.sh`) | Command | API key | Stored login | Cheap default |
|---|---|---|---|---|---|
| `gemini` | `@google/gemini-cli@0.62.0` | `gemini --acp` | `GEMINI_API_KEY` | `~/.gemini/oauth_creds.json` | `--model gemini-3.1-flash-lite` (`SMOKE_GEMINI_MODEL`) |
| `claude` | `@agentclientprotocol/claude-agent-acp@0.85.0` (needs Node 22) | `claude-agent-acp` | `ANTHROPIC_API_KEY` | `~/.claude/.credentials.json` | `ANTHROPIC_MODEL=haiku` (`SMOKE_CLAUDE_MODEL`) |
| `codex` | `@agentclientprotocol/codex-acp@2.1.1` | `codex-acp` | `OPENAI_API_KEY` (`CODEX_API_KEY` wins if set) | `~/.codex/auth.json` | `gpt-6-luna`, reasoning effort `low` via `CODEX_CONFIG` (`SMOKE_CODEX_MODEL`) |

The `@zed-industries/claude-code-acp`, `@zed-industries/claude-agent-acp` and
`@zed-industries/codex-acp` packages are deprecated in favour of the `@agentclientprotocol/` ones.
`--experimental-acp` is Gemini CLI's deprecated alias for `--acp`.

To bump an agent, change its version in `smoke.sh`, run it locally with credentials, then commit.

## Running locally

```bash
integration-testing/programs/smoke/run-smoke.sh            # all three
integration-testing/programs/smoke/run-smoke.sh codex --skip-sdk-install
integration-testing/programs/smoke/run-smoke.sh all --auth none
```

It installs the SDK from this checkout, builds the client, and installs each pinned agent into
`integration-testing/.cache/smoke/npm-<agent>` (never globally). Logs go to
`integration-testing/logs/smoke/`. Prerequisites: JDK 17+, Node 22 (Node 20 works for Gemini and
Codex), npm.

`--auth` (or `SMOKE_AUTH_MODE`) chooses the credentials:

- `login`: the CLI's own stored login (subscription or OAuth), under your real `$HOME`. API keys are
  removed from the agent's environment, so the login pays.
- `key`: the API key from your environment, under a throwaway `$HOME`, so no stored login is read
  and nothing is written into your configuration. This is what CI runs.
- `none`: a throwaway `$HOME` and no keys. Only `initialize` and `session/new` run.
- `auto` (default): `login` when the CLI's login file exists (never under `CI`), else `key` when the
  key is set, else `none`. Gemini prefers the key. As observed on 2026-10-01, Google refuses the
  personal Google login for this client ("no longer supported for Gemini Code Assist for
  individuals"), so that login fails `session/new`.

Login mode writes a session into the CLI's own history (`~/.codex/sessions`, `~/.claude/projects`)
like any other use.

**From inside a Claude Code session**, launch the `claude` cell through `~/scripts/claude-run.sh`
(the adapter starts Claude Code, and nested launches are blocked):
`~/scripts/claude-run.sh 'integration-testing/programs/smoke/run-smoke.sh claude --skip-sdk-install'`.

`configs/smoke-<agent>.json` run the same cell through `RunScenario`
(`jbang RunScenario.java smoke-codex`). They carry the `smoke` tag, so `run-all.sh` runs them only
when asked.

## Secrets: an org-admin act

Repository secrets on `agentclientprotocol/java-sdk` need admin rights, which maintainers do not
have. An org admin adds them under Settings > Secrets and variables > Actions > Repository secrets:

| Secret | Where to get the key | Recommended cap |
|---|---|---|
| `GEMINI_API_KEY` | Google AI Studio, <https://aistudio.google.com/apikey>, in a dedicated project | A free-tier key costs nothing (rate-limited). On a paid project, set a Cloud Billing budget alert. Budgets alert but do not cap. |
| `ANTHROPIC_API_KEY` | Anthropic Console, <https://console.anthropic.com/settings/keys>, in a dedicated workspace | Workspace spend limit of $5/month |
| `OPENAI_API_KEY` | OpenAI Platform, <https://platform.openai.com/api-keys>, in a dedicated project | Project budget of $5/month |

Use dedicated, smoke-only keys, so they can be capped, rotated and revoked on their own. Until a
secret exists, its job prints `SKIPPED: secret X not configured` and succeeds. Nothing in the
workflow needs editing when the keys are added.

**Optional hardening.** An environment named `smoke` with required reviewers gates every run on an
approval, and secrets scoped to it reach only these jobs. Creating environments is also an admin
act. To use it, create the environment, add the three secrets there instead of at repository
level, and uncomment `environment: smoke` in the workflow.

## CI

`cross-sdk-smoke.yml` is `workflow_dispatch` only. It has one matrix job per agent (`fail-fast: false`),
and each job receives only its own agent's key. Inputs:

- `agent`: `all` or one agent.
- `keyless`: without a secret, run the keyless steps (`initialize`, `session/new`) instead of
  skipping. This is useful to check the plumbing before keys exist.

**Enabling the nightly.** Once the secrets exist, uncomment the `schedule:` block in
`cross-sdk-smoke.yml`. It runs at 05:41 UTC, after the 04:17 cross-SDK run.

## Cost per run

These figures assume one prompt per agent with the cheap defaults. Most of the input is the agent's own system
prompt and tool definitions, not the prompt:

- **Claude** (Haiku): the adapter reports its cost. Measured locally on 2026-10-01: about 23k
  context tokens and **$0.021** per run. A CI run, with a fresh `$HOME` and no user `CLAUDE.md`, is
  a little less.
- **Codex** (`gpt-6-luna`, low effort): measured locally at about 15.5k context tokens per run. The
  adapter reports no cost; at small-model API rates that is around a cent. Confirm it on the first
  keyed run.
- **Gemini** (`gemini-3.1-flash-lite`): not measured, because no key was available locally. A
  similar-sized context on Flash-Lite should cost well under a cent, and nothing on a free-tier
  key.

That is about **$0.05 per full run**, or about $1.50 a month as a nightly.
