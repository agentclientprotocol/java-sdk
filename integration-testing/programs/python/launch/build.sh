#!/usr/bin/env bash
# Checks the Python interop programs' environment: the peer checkout's venv (built by peers.json,
# with the SDK's [http] extra and Hypercorn) imports every SDK module the programs use.
# Byte-compiles the SDK sources once (into the peer checkout, not here): the programs run with -B,
# and an uncompiled acp.schema adds about 2 s to every start. Idempotent and fast.
# Launcher contract: integration-testing/README.md, "Contracts". Env: PY_SDK (the python-sdk checkout under test).
set -euo pipefail
py="${PY_SDK:?PY_SDK is not set}/.venv/bin/python"
[[ -x "$py" ]] || { echo "build.sh: no venv python at $py (the python-sdk peer build did not run)" >&2; exit 1; }
"$py" -m compileall -q "$PY_SDK/src" >/dev/null
exec "$py" -B -c 'import acp, acp.http.asgi, acp.http.client, acp.ws.client, acp.ws.server, hypercorn.asyncio'
