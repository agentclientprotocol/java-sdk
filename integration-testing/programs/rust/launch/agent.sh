#!/usr/bin/env bash
# Starts the Rust interop agent: --transport stdio | --transport http|ws --port <port>.
# Launcher contract: integration-testing/README.md, "Contracts". Prints nothing itself: on stdio,
# stdout is the protocol stream. Env: CARGO_TARGET_DIR (where build.sh put the binaries).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${CARGO_TARGET_DIR:-$here/target}/release/interop-agent" "$@"
