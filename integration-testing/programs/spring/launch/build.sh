#!/usr/bin/env bash
# Builds the Spring Boot smoke programs against the SDK installed from this checkout, with the
# Spring Boot version of acp-spring-boot-starter. Launcher contract: integration-testing/README.md,
# "Contracts". Env: MVNW, ACP_VERSION, IT_ROOT.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
boot_version="$(sed -n 's:.*<spring-boot.version>\(.*\)</spring-boot.version>.*:\1:p' "${IT_ROOT:?IT_ROOT is not set}/../acp-spring-boot-starter/pom.xml" | head -1)"
[ -n "$boot_version" ] || { echo "build.sh: no <spring-boot.version> in acp-spring-boot-starter/pom.xml" >&2; exit 1; }
exec "${MVNW:?MVNW is not set}" -q -B -Dacp.version="${ACP_VERSION:?ACP_VERSION is not set}" \
    -Dspring-boot.version="$boot_version" compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
