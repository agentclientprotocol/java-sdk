#!/usr/bin/env bash
# Starts the raw client: --transport stdio (env AGENT_CMD) | --transport http|ws --url <url>
# [--cases <ids>] [--target <name>]. Launcher contract: integration-testing/README.md, "Contracts".
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 -u "$here/raw.py" client "$@"
