#!/usr/bin/env bash
# Builds the Quarkus smoke programs against the SDK installed from this checkout, with the root
# pom's Quarkus version. The agent transport and whether the agent is served are build-time
# settings of acp-quarkus, so there are three packages:
#   target/quarkus-app the agent over HTTP and WebSocket (application.properties)
#   target/stdio       the agent over stdio
#   target/client      no agent served: the client program (launch/client.sh)
# Launcher contract: integration-testing/README.md, "Contracts". Env: MVNW, ACP_VERSION, IT_ROOT.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
quarkus_version="$(sed -n 's:.*<quarkus.version>\(.*\)</quarkus.version>.*:\1:p' "${IT_ROOT:?IT_ROOT is not set}/../pom.xml" | head -1)"
[ -n "$quarkus_version" ] || { echo "build.sh: no <quarkus.version> in the root pom" >&2; exit 1; }
mvn_q() {
    "${MVNW:?MVNW is not set}" -q -B -Dacp.version="${ACP_VERSION:?ACP_VERSION is not set}" \
        -Dquarkus.version="$quarkus_version" "$@"
}
mvn_q package
mvn_q package -Dquarkus.acp.agent.transport.type=stdio -Dquarkus.package.output-directory=stdio
mvn_q package -Dquarkus.acp.agent.enabled=false -Dquarkus.package.output-directory=client
