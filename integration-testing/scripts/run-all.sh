#!/usr/bin/env bash
#
# Run every cross-SDK and load scenario and print a pass/fail table.
#
#   integration-testing/scripts/run-all.sh                               # peers at main
#   integration-testing/scripts/run-all.sh --peers-ref v1.0.0            # every peer at a tag
#   integration-testing/scripts/run-all.sh --peer typescript-sdk=v0.5.0  # one peer at a tag
#   integration-testing/scripts/run-all.sh --only interop-ts-server,load-50
#
# Installs the SDK from this checkout once (./mvnw -DskipTests install), then runs each scenario
# with --skip-sdk-install. Exits non-zero if any scenario fails; a declared expected failure
# (for example the Java client -> Python server reconnect-then-load 404) does not fail the run.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$IT_DIR/.." && pwd)"
cd "$IT_DIR"

ONLY=""
PASS_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --only) ONLY="$2"; shift 2 ;;
        --only=*) ONLY="${1#--only=}"; shift ;;
        --peer|--peers-ref) PASS_ARGS+=("$1" "$2"); shift 2 ;;
        --peer=*|--peers-ref=*) PASS_ARGS+=("$1"); shift ;;
        -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

missing=()
for tool in jbang java jcmd node npm cargo python3 git; do
    command -v "$tool" > /dev/null 2>&1 || missing+=("$tool")
done
if [ ${#missing[@]} -gt 0 ]; then
    echo "Missing prerequisites: ${missing[*]} (see integration-testing/README.md)" >&2
    exit 2
fi

# Interop first (fast, and a broken transport shows there first), then load.
SCENARIOS=(
    interop-java-java
    interop-ts-server interop-rust-server interop-python-server
    interop-ts-client interop-rust-client interop-python-client
    load-50 load-300 load-1000 load-shared-300
)
for f in configs/*.json; do
    s="$(basename "$f" .json)"
    [[ " ${SCENARIOS[*]} " == *" $s "* ]] || SCENARIOS+=("$s")
done
if [ -n "$ONLY" ]; then
    IFS=',' read -r -a SCENARIOS <<< "$ONLY"
fi

mkdir -p logs/run-all
echo "Installing the SDK from $REPO_DIR"
if ! (cd "$REPO_DIR" && timeout 900 ./mvnw -q -B -DskipTests install) > logs/run-all/sdk-install.log 2>&1; then
    echo "SDK install failed; see $IT_DIR/logs/run-all/sdk-install.log" >&2
    tail -40 logs/run-all/sdk-install.log >&2
    exit 1
fi
timeout 600 jbang build RunScenario.java > logs/run-all/jbang-build.log 2>&1 || {
    echo "jbang build failed; see logs/run-all/jbang-build.log" >&2; cat logs/run-all/jbang-build.log >&2; exit 1; }

START=$(date +%s)
declare -A STATUS SECS NOTES
FAILED=0
for s in "${SCENARIOS[@]}"; do
    echo
    echo "################ $s"
    rm -f "logs/$s/result.txt"
    timeout 1800 jbang RunScenario.java "$s" --skip-sdk-install "${PASS_ARGS[@]}" 2>&1 | tee "logs/run-all/$s.console.log"
    code=${PIPESTATUS[0]}
    if [ -f "logs/$s/result.txt" ]; then
        STATUS[$s]="$(sed -n 's/^status=//p' "logs/$s/result.txt")"
        xfail="$(sed -n 's/^xfail=//p' "logs/$s/result.txt")"
        [ "${STATUS[$s]}" = "PASS" ] && [ "${xfail:-0}" != "0" ] && STATUS[$s]="PASS ($xfail xfail)"
        SECS[$s]="$(sed -n 's/^seconds=//p' "logs/$s/result.txt")"
        NOTES[$s]="$(sed -n 's/^notes=//p' "logs/$s/result.txt")"
        [ "$code" -ne 0 ] && [ "${STATUS[$s]}" != "FAIL" ] && STATUS[$s]="FAIL (exit $code)"
    else
        STATUS[$s]="FAIL (exit $code, no result)"
        SECS[$s]="-"
        NOTES[$s]=""
    fi
    [[ "${STATUS[$s]}" == PASS* ]] || FAILED=$((FAILED + 1))
done
TOTAL=$(( $(date +%s) - START ))

table() {
    printf '%-24s %-16s %8s  %s\n' "Scenario" "Result" "Time (s)" "Notes"
    printf '%-24s %-16s %8s  %s\n' "------------------------" "----------------" "--------" "-----"
    for s in "${SCENARIOS[@]}"; do
        printf '%-24s %-16s %8s  %s\n' "$s" "${STATUS[$s]}" "${SECS[$s]}" "${NOTES[$s]}"
    done
    echo
    echo "${#SCENARIOS[@]} scenarios, $FAILED failed, ${TOTAL} s total"
}

echo
echo "========================================================================"
table | tee logs/run-all/summary.txt
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "### Cross-SDK and load scenarios"
        echo
        echo "| Scenario | Result | Time (s) | Notes |"
        echo "|---|---|---|---|"
        for s in "${SCENARIOS[@]}"; do
            echo "| $s | ${STATUS[$s]} | ${SECS[$s]} | ${NOTES[$s]//|/\\|} |"
        done
        echo
        echo "${#SCENARIOS[@]} scenarios, $FAILED failed, ${TOTAL} s total"
    } >> "$GITHUB_STEP_SUMMARY"
fi
[ "$FAILED" -eq 0 ]
