#!/usr/bin/env bash
# Starts the Quarkus smoke agent: --transport stdio | --transport http|ws --port <port> (one server
# serves HTTP and WebSocket). Launcher contract: integration-testing/README.md, "Contracts".
# Prints nothing itself: on stdio, stdout is the protocol stream. JAVA_OPTS, if set, is passed on.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
usage() { echo "usage: agent.sh --transport stdio | --transport http|ws --port <port>" >&2; exit 2; }
transport="" port=""
while [ $# -gt 0 ]; do
    case "$1" in
        --transport) transport="${2:-}"; shift 2 ;;
        --port) port="${2:-}"; shift 2 ;;
        *) usage ;;
    esac
done
case "$transport" in
    stdio)
        # shellcheck disable=SC2086
        exec java ${JAVA_OPTS:-} -jar "$here/target/stdio/quarkus-run.jar" ;;
    http|ws)
        [ -n "$port" ] || usage
        # shellcheck disable=SC2086
        exec java ${JAVA_OPTS:-} -Dinterop.ready=true -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port="$port" \
            -jar "$here/target/quarkus-app/quarkus-run.jar" ;;
    *) usage ;;
esac
