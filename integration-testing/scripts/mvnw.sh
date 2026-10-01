#!/usr/bin/env bash
# The Maven wrapper of this checkout, pinned to the suite's local repository (IT_M2_REPO, resolved
# by m2-repo.sh). Every Maven call of the suite (SDK install, program builds) goes through this,
# so a SNAPSHOT is installed into and resolved from one repository only. Runs in the caller's
# directory, like ./mvnw.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=m2-repo.sh
. "$here/m2-repo.sh"
exec "$here/../../mvnw" -Dmaven.repo.local="$IT_M2_REPO" "$@"
