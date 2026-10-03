#!/usr/bin/env bash
# Builds the Micronaut-hosted agent against the SDK installed from this checkout.
# Launcher contract: integration-testing/README.md, "Contracts". Env: MVNW, ACP_VERSION.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec "${MVNW:?MVNW is not set}" -q -B -Dacp.version="${ACP_VERSION:?ACP_VERSION is not set}" \
    compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
