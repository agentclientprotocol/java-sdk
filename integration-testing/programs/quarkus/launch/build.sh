#!/usr/bin/env bash
# Builds the Quarkus-hosted interop agent against the SDK installed from this checkout, with the
# root pom's Quarkus version. Launcher contract: integration-testing/README.md, "Contracts".
# Env: MVNW, ACP_VERSION, IT_ROOT.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
quarkus_version="$(sed -n 's:.*<quarkus.version>\(.*\)</quarkus.version>.*:\1:p' "${IT_ROOT:?IT_ROOT is not set}/../pom.xml" | head -1)"
[ -n "$quarkus_version" ] || { echo "build.sh: no <quarkus.version> in the root pom" >&2; exit 1; }
exec "${MVNW:?MVNW is not set}" -q -B -Dacp.version="${ACP_VERSION:?ACP_VERSION is not set}" \
    -Dquarkus.version="$quarkus_version" package
