#!/usr/bin/env bash
# Starts the Python interop agent: --transport stdio | --transport http|ws --port <port>.
# Launcher contract: integration-testing/README.md, "Contracts". Prints nothing itself: on stdio,
# stdout is the protocol stream. Env: PY_SDK (the python-sdk checkout under test).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
exec "${PY_SDK:?PY_SDK is not set}/.venv/bin/python" -B "$here/interop/agent.py" "$@"
