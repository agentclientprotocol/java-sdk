#!/usr/bin/env bash
# Starts the Java interop agent: --transport stdio | --transport http|ws --port <port>.
# Launcher contract: integration-testing/README.md, "Contracts". Prints nothing itself: on stdio,
# stdout is the protocol stream. JAVA_OPTS, if set, is passed to the JVM.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -cp "$here/target/classes:$(cat "$here/target/classpath.txt")" interop.Agent "$@"
