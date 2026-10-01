# shellcheck shell=bash
# Sourced, not run. Resolves the one Maven local repository the suite uses and exports it:
#   IT_M2_REPO   if set, used as is (made absolute);
#                else ~/.m2/repository when CI=true (keeps the CI cache);
#                else integration-testing/.cache/m2 (per checkout, git-ignored).
#   JBANG_REPO   = IT_M2_REPO, so JBang resolves into the same repository.
# Every Maven call of the suite goes through scripts/mvnw.sh, which passes
# -Dmaven.repo.local="$IT_M2_REPO". See README.md, Contracts section 7.
_it_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -z "${IT_M2_REPO:-}" ]; then
    if [ "${CI:-}" = "true" ]; then
        IT_M2_REPO="$HOME/.m2/repository"
    else
        IT_M2_REPO="$_it_dir/.cache/m2"
    fi
fi
mkdir -p "$IT_M2_REPO"
IT_M2_REPO="$(cd "$IT_M2_REPO" && pwd)"
export IT_M2_REPO
export JBANG_REPO="$IT_M2_REPO"
unset _it_dir
