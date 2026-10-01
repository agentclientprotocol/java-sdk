#!/usr/bin/env bash
# Starts the Python interop client: --transport stdio (env AGENT_CMD) | --transport http|ws --url <url>.
# Env STEPS (required), STEP_TIMEOUT_MS (default 15000), PY_SDK (the python-sdk checkout under test).
# Launcher contract: integration-testing/README.md, "Contracts".
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
exec "${PY_SDK:?PY_SDK is not set}/.venv/bin/python" -B "$here/interop/client.py" "$@"
