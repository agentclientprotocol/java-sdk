#!/usr/bin/env bash
# Starts the Kotlin interop client: --transport stdio (env AGENT_CMD) | --transport ws --url <url>.
# Env STEPS (required), STEP_TIMEOUT_MS (default 15000). Launcher contract:
# integration-testing/README.md, "Contracts". JAVA_OPTS, if set, reaches the JVM.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$here/build/install/kt-peer/bin/kt-peer" client "$@"
