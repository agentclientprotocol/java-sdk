#!/usr/bin/env bash
# Starts the Kotlin interop agent: --transport stdio | --transport ws --port <port> (no http: the
# Kotlin SDK has no Streamable HTTP). Launcher contract: integration-testing/README.md, "Contracts".
# Prints nothing itself: on stdio, stdout is the protocol stream. JAVA_OPTS, if set, reaches the JVM.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$here/build/install/kt-peer/bin/kt-peer" agent "$@"
