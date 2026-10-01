#!/usr/bin/env bash
# Starts the TypeScript interop client: --transport stdio (env AGENT_CMD) | --transport http|ws --url <url>.
# Env STEPS (required), STEP_TIMEOUT_MS (default 15000), TS_SDK (the built typescript-sdk checkout).
# Launcher contract: integration-testing/README.md, "Contracts".
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec node "$here/interop/client.mjs" "$@"
