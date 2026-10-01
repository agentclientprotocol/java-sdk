#!/usr/bin/env bash
# Starts the Java interop client: --transport stdio (env AGENT_CMD) | --transport http|ws --url <url>.
# Env STEPS (required), STEP_TIMEOUT_MS (default 15000). Launcher contract: integration-testing/README.md,
# "Contracts". JAVA_OPTS, if set, is passed to the JVM.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -cp "$here/target/classes:$(cat "$here/target/classpath.txt")" interop.Client "$@"
