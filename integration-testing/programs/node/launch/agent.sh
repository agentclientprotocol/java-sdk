#!/usr/bin/env bash
# Starts the TypeScript interop agent: --transport stdio | --transport http|ws --port <port>.
# Env TS_SDK (the built typescript-sdk checkout). Launcher contract: integration-testing/README.md,
# "Contracts". Prints nothing itself: on stdio, stdout is the protocol stream.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec node "$here/interop/agent.mjs" "$@"
