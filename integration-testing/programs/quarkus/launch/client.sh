#!/usr/bin/env bash
# Starts the Quarkus smoke client: --transport http|ws --url <url> (env STEPS). The ACP client is
# the bean acp-quarkus builds from quarkus.acp.client.*. Launcher contract:
# integration-testing/README.md, "Contracts". JAVA_OPTS, if set, is passed on.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
usage() { echo "usage: client.sh --transport http|ws --url <url>   (env STEPS)" >&2; exit 2; }
transport="" url=""
while [ $# -gt 0 ]; do
    case "$1" in
        --transport) transport="${2:-}"; shift 2 ;;
        --url) url="${2:-}"; shift 2 ;;
        *) usage ;;
    esac
done
case "$transport" in
    http) key=quarkus.acp.client.transport.http.uri ;;
    ws) key=quarkus.acp.client.transport.websocket.uri ;;
    *) usage ;;
esac
[ -n "$url" ] || usage
# The client build serves no agent; its HTTP server takes an ephemeral port on loopback.
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -Dinterop.mode=client -D"$key=$url" \
    -Dquarkus.acp.client.capabilities.read-text-file=true \
    -Dquarkus.acp.client.capabilities.elicitation-form=true \
    -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port=0 -Dquarkus.banner.enabled=false \
    -jar "$here/target/client/quarkus-run.jar"
