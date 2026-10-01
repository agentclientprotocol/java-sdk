#!/usr/bin/env bash
# Starts the Rust interop client: --transport stdio (env AGENT_CMD) | --transport http|ws --url <url>.
# Env STEPS (required), STEP_TIMEOUT_MS (default 15000), CARGO_TARGET_DIR (where build.sh put the
# binaries). Launcher contract: integration-testing/README.md, "Contracts".
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${CARGO_TARGET_DIR:-$here/target}/release/interop-client" "$@"
