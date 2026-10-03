#!/usr/bin/env bash
# Starts the Quarkus-hosted interop agent: --transport http|ws --port <port> (one server serves
# both). This program is built for HTTP only, so stdio is refused. Launcher contract:
# integration-testing/README.md, "Contracts". JAVA_OPTS, if set, is passed to the JVM.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
transport="" port=""
while [ $# -gt 0 ]; do
    case "$1" in
        --transport) transport="${2:-}"; shift 2 ;;
        --port) port="${2:-}"; shift 2 ;;
        *) echo "usage: agent.sh --transport http|ws --port <port>" >&2; exit 2 ;;
    esac
done
case "$transport" in
    http|ws) [ -n "$port" ] || { echo "usage: agent.sh --transport http|ws --port <port>" >&2; exit 2; } ;;
    *) echo "agent.sh: the Quarkus agent serves http and ws only" >&2; exit 2 ;;
esac
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port="$port" \
    -jar "$here/target/quarkus-app/quarkus-run.jar"
