#!/usr/bin/env bash
# Starts the Spring Boot smoke client: --transport http|ws --url <url> (env STEPS). The ACP client
# is the bean acp-spring-boot-starter builds from spring.acp.client.*. Launcher contract:
# integration-testing/README.md, "Contracts". JAVA_OPTS, if set, is passed on.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -cp "$here/target/classes:$(cat "$here/target/classpath.txt")" interop.spring.client.SpringClientApp "$@"
