#!/usr/bin/env bash
#
# Real-agent smoke tier: the Java client from this checkout against published ACP agent CLIs.
#
#   integration-testing/programs/smoke/run-smoke.sh [gemini|claude|codex|all] [options]
#
#   --auth auto|login|key|none   how each agent is authenticated (default auto; see smoke.sh)
#   --skip-sdk-install           reuse the SDK already installed in ~/.m2 from this checkout
#
# Installs the SDK from this checkout, builds the smoke client, installs each pinned agent into
# .cache/smoke (never globally) and runs it. Logs go to integration-testing/logs/smoke/<agent>.log.
# Exits non-zero if any STEP failed. A SKIP (no credentials) is not a failure.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_DIR="$(cd "$IT_DIR/.." && pwd)"

WHICH="all"
SKIP_INSTALL=""
while [ $# -gt 0 ]; do
    case "$1" in
        gemini|claude|codex|all) WHICH="$1"; shift ;;
        --auth) export SMOKE_AUTH_MODE="$2"; shift 2 ;;
        --auth=*) export SMOKE_AUTH_MODE="${1#--auth=}"; shift ;;
        --skip-sdk-install) SKIP_INSTALL=1; shift ;;
        -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
AGENTS=(gemini claude codex)
[ "$WHICH" = "all" ] || AGENTS=("$WHICH")

for tool in java node npm timeout; do
    command -v "$tool" > /dev/null 2>&1 || { echo "Missing prerequisite: $tool" >&2; exit 2; }
done

LOGS="$IT_DIR/logs/smoke"
mkdir -p "$LOGS"
ACP_VERSION="$(sed -n '/<parent>/,/<\/parent>/d; s#^    <version>\(.*\)</version>#\1#p' "$REPO_DIR/pom.xml" | head -1)"

if [ -z "$SKIP_INSTALL" ]; then
    echo "Installing the SDK $ACP_VERSION from $REPO_DIR"
    (cd "$REPO_DIR" && timeout 900 ./mvnw -q -B -DskipTests install) > "$LOGS/sdk-install.log" 2>&1 || {
        echo "SDK install failed; see $LOGS/sdk-install.log" >&2; tail -40 "$LOGS/sdk-install.log" >&2; exit 1; }
fi
echo "Building the smoke client against acp $ACP_VERSION"
(cd "$SCRIPT_DIR" && timeout 600 "$REPO_DIR/mvnw" -q -B -Dacp.version="$ACP_VERSION" compile \
    dependency:build-classpath -Dmdep.outputFile=target/classpath.txt) > "$LOGS/build.log" 2>&1 || {
    echo "Smoke client build failed; see $LOGS/build.log" >&2; tail -40 "$LOGS/build.log" >&2; exit 1; }

declare -A STATUS DETAIL
FAILED=0
for a in "${AGENTS[@]}"; do
    echo
    echo "################ smoke $a"
    "$SCRIPT_DIR/smoke.sh" "$a" 2>&1 | tee "$LOGS/$a.log"
    code=${PIPESTATUS[0]}
    auth="$(sed -n 's/^SMOKE agent=.* auth=\(.*\)$/\1/p' "$LOGS/$a.log" | head -1)"
    steps="$(grep -E '^STEP ' "$LOGS/$a.log" | awk '{printf "%s%s=%s", (NR>1?" ":""), $2, $3}')"
    if [ "$code" -eq 0 ] && ! grep -qE '^STEP [^ ]+ FAIL' "$LOGS/$a.log"; then
        STATUS[$a]="PASS"
    else
        STATUS[$a]="FAIL (exit $code)"
        FAILED=$((FAILED + 1))
    fi
    DETAIL[$a]="auth=${auth:-?} ${steps}"
done

echo
echo "========================================================================"
for a in "${AGENTS[@]}"; do
    printf '%-8s %-14s %s\n' "$a" "${STATUS[$a]}" "${DETAIL[$a]}"
done | tee "$LOGS/summary.txt"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "### Real-agent smoke"
        echo
        echo "| Agent | Result | Steps |"
        echo "|---|---|---|"
        for a in "${AGENTS[@]}"; do echo "| $a | ${STATUS[$a]} | ${DETAIL[$a]} |"; done
    } >> "$GITHUB_STEP_SUMMARY"
fi
[ "$FAILED" -eq 0 ]
