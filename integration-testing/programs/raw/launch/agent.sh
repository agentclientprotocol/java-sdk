#!/usr/bin/env bash
# Starts the raw agent: --transport stdio | --transport http|ws --port <port> [--target <name>].
# Launcher contract: integration-testing/README.md, "Contracts". Prints nothing itself.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 -u "$here/raw.py" agent "$@"
