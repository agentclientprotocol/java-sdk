#!/usr/bin/env bash
#
# Run the smoke client against ONE real ACP agent CLI. The client must already be built
# (run-smoke.sh does that; so does the "build" of configs/smoke-*.json).
#
#   programs/smoke/smoke.sh gemini|claude|codex
#
# Environment:
#   SMOKE_AUTH_MODE  auto (default) | login | key | none
#       login  the CLI's own stored login (subscription/OAuth) under the real $HOME; API keys
#              are removed from the agent's environment so the login is what pays.
#       key    the agent's API key from the environment, under a fresh throwaway $HOME, so no
#              stored login is used and none is written. This is what CI runs.
#       none   a fresh $HOME and no keys: initialize and session/new only; the prompt SKIPs.
#       auto   login when the CLI's login file exists (never in CI), else key when the key is
#              set, else none. Gemini prefers the key: as observed 2026-10-01, Google refuses
#              the personal Google login ("no longer supported for Gemini Code Assist for
#              individuals"), so that login fails session/new.
#   SMOKE_GEMINI_MODEL / SMOKE_CLAUDE_MODEL / SMOKE_CODEX_MODEL   override the cheap defaults
#   SMOKE_CACHE      where the pinned agent packages are installed (default .cache/smoke)
#   SMOKE_TOTAL_TIMEOUT  seconds for the whole run (default 300)
#   SMOKE_JAVA_OPTS  extra JVM options, e.g. -Dacp.level=DEBUG for the SDK's protocol log
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CACHE="${SMOKE_CACHE:-$IT_DIR/.cache/smoke}"
AGENT="${1:-}"
MODE="${SMOKE_AUTH_MODE:-auto}"
TOTAL="${SMOKE_TOTAL_TIMEOUT:-300}"

# Every key any of the agents reads. Each run removes the ones its mode must not see.
ALL_KEYS=(GEMINI_API_KEY GOOGLE_API_KEY ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN OPENAI_API_KEY CODEX_API_KEY)

# Pinned agents. Bump a version here, run the smoke locally, then commit.
AGENT_ENV=()
PREFER=login
case "$AGENT" in
    gemini)
        # https://github.com/google-gemini/gemini-cli : `gemini --acp` (--experimental-acp is the
        # deprecated alias). API key: GEMINI_API_KEY (Gemini Developer API). Login: Google OAuth
        # cached in ~/.gemini/oauth_creds.json.
        PKG="@google/gemini-cli@0.62.0"
        BIN="gemini"
        ARGS="--acp --model ${SMOKE_GEMINI_MODEL:-gemini-3.1-flash-lite}"
        KEYS=(GEMINI_API_KEY)
        LOGIN_FILE="$HOME/.gemini/oauth_creds.json"
        PREFER=key
        ;;
    claude)
        # https://github.com/agentclientprotocol/claude-agent-acp (formerly
        # @zed-industries/claude-code-acp, deprecated). API key: ANTHROPIC_API_KEY. Login: Claude
        # Code's stored login (~/.claude/.credentials.json). Model from ANTHROPIC_MODEL.
        PKG="@agentclientprotocol/claude-agent-acp@0.85.0"
        BIN="claude-agent-acp"
        ARGS=""
        KEYS=(ANTHROPIC_API_KEY)
        LOGIN_FILE="$HOME/.claude/.credentials.json"
        AGENT_ENV+=("ANTHROPIC_MODEL=${SMOKE_CLAUDE_MODEL:-haiku}")
        ;;
    codex)
        # https://github.com/agentclientprotocol/codex-acp (formerly @zed-industries/codex-acp,
        # deprecated). API key: OPENAI_API_KEY (or CODEX_API_KEY, which wins). Login: ChatGPT,
        # stored in ~/.codex/auth.json. Model and effort from CODEX_CONFIG.
        PKG="@agentclientprotocol/codex-acp@2.1.1"
        BIN="codex-acp"
        ARGS=""
        KEYS=(OPENAI_API_KEY CODEX_API_KEY)
        LOGIN_FILE="${CODEX_HOME:-$HOME/.codex}/auth.json"
        AGENT_ENV+=("CODEX_CONFIG={\"model\":\"${SMOKE_CODEX_MODEL:-gpt-6-luna}\",\"model_reasoning_effort\":\"low\"}"
            "NO_BROWSER=1")
        ;;
    *)
        echo "usage: $0 gemini|claude|codex" >&2
        exit 2
        ;;
esac

key_set=""
for k in "${KEYS[@]}"; do
    [ -n "${!k:-}" ] && key_set="$k"
done

if [ "$MODE" = "auto" ]; then
    if [ "$PREFER" = "key" ] && [ -n "$key_set" ]; then
        MODE=key
    elif [ -z "${CI:-}" ] && [ -s "$LOGIN_FILE" ]; then
        MODE=login
    elif [ -n "$key_set" ]; then
        MODE=key
    else
        MODE=none
    fi
fi
case "$MODE" in
    login) [ -s "$LOGIN_FILE" ] || { echo "SMOKE_AUTH_MODE=login but $LOGIN_FILE does not exist" >&2; exit 2; } ;;
    key) [ -n "$key_set" ] || { echo "SMOKE_AUTH_MODE=key but none of ${KEYS[*]} is set" >&2; exit 2; } ;;
    none) ;;
    *) echo "unknown SMOKE_AUTH_MODE=$MODE" >&2; exit 2 ;;
esac

# Install the pinned agent into its own npm prefix (never global).
PREFIX="$CACHE/npm-$AGENT"
if [ "$(cat "$PREFIX/.pinned" 2>/dev/null)" != "$PKG" ]; then
    echo "Installing $PKG into $PREFIX"
    rm -rf "$PREFIX"
    mkdir -p "$PREFIX"
    timeout 600 npm install --prefix "$PREFIX" --no-fund --no-audit --loglevel=error "$PKG" >&2
    echo "$PKG" > "$PREFIX/.pinned"
fi
AGENT_BIN="$PREFIX/node_modules/.bin/$BIN"
[ -x "$AGENT_BIN" ] || { echo "$AGENT_BIN missing after install" >&2; exit 2; }

if [ "$AGENT" = "claude" ]; then
    node_major="$(node -p 'process.versions.node.split(".")[0]')"
    [ "$node_major" -ge 22 ] || echo "WARN: $PKG declares node >=22; running on node $(node --version)"
fi

# The agent's environment for this mode. Keys are never printed. env(1) takes every -u before
# the first NAME=VALUE, hence two lists.
UNSETS=()
SETS=()
drop_keys() { # $1: keep-mine | drop-all
    local k mine keep
    for k in "${ALL_KEYS[@]}"; do
        keep=""
        if [ "$1" = "keep-mine" ]; then
            for mine in "${KEYS[@]}"; do [ "$k" = "$mine" ] && keep=1; done
        fi
        [ -n "$keep" ] || UNSETS+=(-u "$k")
    done
}
if [ "$MODE" = "login" ]; then
    drop_keys drop-all
else
    # A fresh home: no stored login is read, and an agent that persists credentials writes
    # them here, not into the developer's configuration.
    ISO="$CACHE/home-$AGENT-$MODE"
    rm -rf "$ISO"
    mkdir -p "$ISO"/{.config,.cache,.local/share,.local/state,.codex}
    SETS+=("HOME=$ISO" "XDG_CONFIG_HOME=$ISO/.config" "XDG_CACHE_HOME=$ISO/.cache"
        "XDG_DATA_HOME=$ISO/.local/share" "XDG_STATE_HOME=$ISO/.local/state" "CODEX_HOME=$ISO/.codex")
    if [ "$MODE" = "key" ]; then
        drop_keys keep-mine
        # codex-acp logs in with the env key only when told to (in the throwaway CODEX_HOME).
        [ "$AGENT" = "codex" ] && SETS+=('DEFAULT_AUTH_REQUEST={"methodId":"api-key"}')
    else
        drop_keys drop-all
    fi
fi

CP="$SCRIPT_DIR/target/classes:$(cat "$SCRIPT_DIR/target/classpath.txt")"
echo "SMOKE agent=$AGENT package=$PKG auth=$MODE"
exec timeout -k 10 "$((TOTAL + 30))" env "${UNSETS[@]}" "${SETS[@]}" "${AGENT_ENV[@]}" \
    SMOKE_AGENT="$AGENT" SMOKE_AUTH="$MODE" SMOKE_TOTAL_TIMEOUT="$TOTAL" \
    AGENT_CMD="$AGENT_BIN${ARGS:+ $ARGS}" \
    java ${SMOKE_JAVA_OPTS:-} -cp "$CP" smoke.SmokeClient
