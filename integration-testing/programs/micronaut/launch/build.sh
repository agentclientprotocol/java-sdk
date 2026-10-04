#!/usr/bin/env bash
# Builds the Micronaut smoke programs against the SDK installed from this checkout, with the root
# pom's Micronaut core and logback versions. Launcher contract: integration-testing/README.md,
# "Contracts". Env: MVNW, ACP_VERSION, IT_ROOT.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
root_pom="${IT_ROOT:?IT_ROOT is not set}/../pom.xml"
prop() { sed -n "s:.*<$1>\(.*\)</$1>.*:\1:p" "$root_pom" | head -1; }
core="$(prop micronaut.core.version)" logback="$(prop logback.version)"
[ -n "$core" ] && [ -n "$logback" ] || { echo "build.sh: no micronaut.core.version or logback.version in the root pom" >&2; exit 1; }
exec "${MVNW:?MVNW is not set}" -q -B -Dacp.version="${ACP_VERSION:?ACP_VERSION is not set}" \
    -Dmicronaut.core.version="$core" -Dlogback.version="$logback" \
    compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
