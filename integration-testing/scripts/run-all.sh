#!/usr/bin/env bash
#
# Run cross-SDK and load scenarios and print a pass/fail table.
#
#   integration-testing/scripts/run-all.sh                               # the default set, peers at main
#   integration-testing/scripts/run-all.sh --peers-ref v1.0.0            # every peer at a tag
#   integration-testing/scripts/run-all.sh --peer typescript-sdk=v0.5.0  # one peer at a tag
#   integration-testing/scripts/run-all.sh --only interop-ts-server,load-50   # names
#   integration-testing/scripts/run-all.sh --only 'x-*-python-*,x-python-*'   # globs
#   integration-testing/scripts/run-all.sh --tag python,stdio            # dash-separated name tokens
#   integration-testing/scripts/run-all.sh --exclude 'load-*' --list     # print the selection only
#   integration-testing/scripts/run-all.sh --jobs 1                      # one scenario at a time
#   integration-testing/scripts/run-all.sh --tag rust --prepare-only     # install, fetch and build only
#
# Selection: with no --only/--tag, every configs/*.json. --only takes comma-separated names or
# globs; --tag takes comma-separated tags, where a scenario's tags are the dash-separated tokens of
# its name (x-java-python-stdio has x, java, python, stdio). --only and --tag together select
# the union; --exclude (globs) removes from it. self-*, smoke-* and *-unstable scenarios are left
# out unless a pattern or tag targets them (--only 'self-*', --only '*-unstable', --tag self,
# --tag smoke, --tag unstable) or names one exactly. An empty selection fails unless --allow-empty.
#
# Installs the SDK from this checkout once (./mvnw -DskipTests install), prepares every peer and
# build the selection needs once (RunScenario --prepare), then runs the scenarios with --prepared,
# --jobs at a time (default: half the CPUs, at most 8), each with its own log directory, temp
# directory and port range; load-* scenarios run afterwards, alone and serially. Exits non-zero
# if any scenario fails; a declared expected failure (for example the Java client -> Python server
# reconnect-then-load 404) does not fail the run.
#
# Concurrent run-alls (several checkouts on one host): each takes an exclusive 10,000-port block
# (IT_PORT_BASE, else the first free of 20000/30000/40000/50000, locked with flock for the whole
# run), and every Maven and JBang call uses one local repository, IT_M2_REPO (default
# integration-testing/.cache/m2; ~/.m2/repository when CI=true). See README.md, Contracts 7.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$IT_DIR/.." && pwd)"
cd "$IT_DIR"

ONLY=""
TAGS=""
EXCLUDE=""
LIST=0
ALLOW_EMPTY=0
PREPARE_ONLY=0
CPUS="$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"
JOBS=$((CPUS / 2))
[ "$JOBS" -gt 8 ] && JOBS=8
[ "$JOBS" -lt 1 ] && JOBS=1
PASS_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --only) ONLY="${ONLY:+$ONLY,}$2"; shift 2 ;;
        --only=*) ONLY="${ONLY:+$ONLY,}${1#--only=}"; shift ;;
        --tag) TAGS="${TAGS:+$TAGS,}$2"; shift 2 ;;
        --tag=*) TAGS="${TAGS:+$TAGS,}${1#--tag=}"; shift ;;
        --exclude) EXCLUDE="${EXCLUDE:+$EXCLUDE,}$2"; shift 2 ;;
        --exclude=*) EXCLUDE="${EXCLUDE:+$EXCLUDE,}${1#--exclude=}"; shift ;;
        --list) LIST=1; shift ;;
        --prepare-only) PREPARE_ONLY=1; shift ;;
        --jobs|-j) JOBS="$2"; shift 2 ;;
        --jobs=*) JOBS="${1#--jobs=}"; shift ;;
        --allow-empty) ALLOW_EMPTY=1; shift ;;
        --peer|--peers-ref) PASS_ARGS+=("$1" "$2"); shift 2 ;;
        --peer=*|--peers-ref=*) PASS_ARGS+=("$1"); shift ;;
        -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

# Order: legacy interop first (fast, and a broken transport shows there first), then the
# generated cells and conformance scenarios, then load, then anything else. Names in the fixed
# lists keep their historical order; the rest of each group is alphabetical.
ORDERED=()
add() { [ -e "configs/$1.json" ] && [[ " ${ORDERED[*]} " != *" $1 "* ]] && ORDERED+=("$1"); return 0; }
for s in interop-java-java interop-ts-server interop-rust-server interop-python-server \
    interop-ts-client interop-rust-client interop-python-client; do add "$s"; done
for group in 'interop-*' 'x-*' 'conf-*' load-50 load-300 load-1000 load-shared-300 'load-*' '*'; do
    for f in configs/$group.json; do add "$(basename "$f" .json)"; done
done

# Default-excluded classes and whether a pattern or tag targets them.
excluded_class() {
    case "$1" in self-*) echo self ;; smoke-*) echo smoke ;; *-unstable) echo unstable ;; *) echo "" ;; esac
}
targets_class() { # <class> <pattern>
    case "$1" in
        self) [[ "$2" == self-* ]] ;;
        smoke) [[ "$2" == smoke-* ]] ;;
        unstable) [[ "$2" == *-unstable ]] ;;
    esac
}

IFS=',' read -r -a ONLY_PATS <<< "$ONLY"
IFS=',' read -r -a TAG_LIST <<< "$TAGS"
IFS=',' read -r -a EXCLUDE_PATS <<< "$EXCLUDE"
SCENARIOS=()
for s in "${ORDERED[@]}"; do
    cls="$(excluded_class "$s")"
    picked=0
    if [ -z "$ONLY" ] && [ -z "$TAGS" ]; then
        [ -z "$cls" ] && picked=1
    else
        for p in "${ONLY_PATS[@]}"; do
            [ -n "$p" ] || continue
            # shellcheck disable=SC2053
            if [[ "$s" == $p ]]; then
                if [ -z "$cls" ] || [ "$s" = "$p" ] || targets_class "$cls" "$p"; then picked=1; fi
            fi
        done
        IFS='-' read -r -a tokens <<< "$s"
        for t in "${TAG_LIST[@]}"; do
            [ -n "$t" ] || continue
            if [[ " ${tokens[*]} " == *" $t "* ]]; then
                if [ -z "$cls" ] || [ "$t" = "$cls" ]; then picked=1; fi
            fi
        done
    fi
    for p in "${EXCLUDE_PATS[@]}"; do
        # shellcheck disable=SC2053
        [ -n "$p" ] && [[ "$s" == $p ]] && picked=0
    done
    [ "$picked" -eq 1 ] && SCENARIOS+=("$s")
done
# A name given exactly that has no config is an error, not an empty match.
for p in "${ONLY_PATS[@]}"; do
    if [ -n "$p" ] && [[ "$p" != *[\*\?\[]* ]] && [ ! -e "configs/$p.json" ]; then
        echo "No such scenario: $p (see configs/)" >&2
        exit 2
    fi
done
case "$JOBS" in ''|*[!0-9]*|0) echo "--jobs needs a positive number, got '$JOBS'" >&2; exit 2 ;; esac
if [ ${#SCENARIOS[@]} -eq 0 ]; then
    if [ "$ALLOW_EMPTY" -eq 1 ]; then
        echo "No scenario matches the selection (--allow-empty): nothing to run"
        exit 0
    fi
    echo "No scenario matches the selection (only='$ONLY' tag='$TAGS' exclude='$EXCLUDE')" >&2
    exit 2
fi
if [ "$LIST" -eq 1 ]; then
    printf '%s\n' "${SCENARIOS[@]}"
    exit 0
fi

# Prerequisites of the selected scenarios only (each CI job installs one peer's toolchain).
need=(jbang java jcmd git)
for s in "${SCENARIOS[@]}"; do
    langs="$(grep -o '"language": *"[a-z]*"' "configs/$s.json" | sed 's/.*"\([a-z]*\)"$/\1/' | sort -u)"
    for l in $langs; do
        case "$l" in
            node|typescript) need+=(node npm) ;;
            rust) need+=(cargo) ;;
            python) need+=(python3) ;;
        esac
    done
done
missing=()
for tool in $(printf '%s\n' "${need[@]}" | sort -u); do
    command -v "$tool" > /dev/null 2>&1 || missing+=("$tool")
done
if [ ${#missing[@]} -gt 0 ]; then
    echo "Missing prerequisites: ${missing[*]} (see integration-testing/README.md)" >&2
    exit 2
fi

mkdir -p logs/run-all
# One Maven local repository for the SDK install, every program build and JBang (IT_M2_REPO).
# shellcheck source=m2-repo.sh
. "$SCRIPT_DIR/m2-repo.sh"
echo "Installing the SDK from $REPO_DIR into $IT_M2_REPO"
if ! (cd "$REPO_DIR" && timeout 900 "$SCRIPT_DIR/mvnw.sh" -q -B -DskipTests install) > logs/run-all/sdk-install.log 2>&1; then
    echo "SDK install failed; see $IT_DIR/logs/run-all/sdk-install.log" >&2
    tail -40 logs/run-all/sdk-install.log >&2
    exit 1
fi
timeout 600 jbang build RunScenario.java > logs/run-all/jbang-build.log 2>&1 || {
    echo "jbang build failed; see logs/run-all/jbang-build.log" >&2; cat logs/run-all/jbang-build.log >&2; exit 1; }

START=$(date +%s)
declare -A STATUS SECS NOTES
FAILED=0

# Phase 1, serial: clone, fetch and build every peer and program the selection needs, once.
# The scenarios then run with --prepared, which touches no shared state (no git, no build).
echo
echo "Preparing peers and builds for ${#SCENARIOS[@]} scenario(s)"
if ! timeout 3600 jbang RunScenario.java --prepare "${SCENARIOS[@]}" "${PASS_ARGS[@]}" \
        > logs/run-all/prepare.console.log 2>&1; then
    echo "WARN preparation failed for some scenarios (they will fail); see logs/run-all/prepare.console.log" >&2
    grep -E 'FAIL|Exception' logs/run-all/prepare.console.log >&2 || true
    [ "$PREPARE_ONLY" -eq 1 ] && exit 1
fi
if [ "$PREPARE_ONLY" -eq 1 ]; then
    tail -1 logs/run-all/prepare.console.log
    exit 0
fi

# This run's port block, 10,000 ports no other run-all on this host uses at the same time:
# IT_PORT_BASE if set, else the first of 20000/30000/40000/50000 whose lock file we can flock.
# The lock is held by file descriptor PORT_LOCK_FD for the life of this shell (children inherit
# it, so a block stays taken while anything this run started is alive) and released when the
# last holder exits, however it exits. All four taken: wait for one.
PORT_BASE="${IT_PORT_BASE:-}"
if [ -n "$PORT_BASE" ]; then
    case "$PORT_BASE" in *[!0-9]*) echo "IT_PORT_BASE needs a number, got '$PORT_BASE'" >&2; exit 2 ;; esac
elif ! command -v flock > /dev/null 2>&1; then
    PORT_BASE=20000
    echo "WARN flock not found: using port block 20000 unlocked (set IT_PORT_BASE for concurrent runs)" >&2
else
    lock_dir="${XDG_RUNTIME_DIR:-/tmp}"
    waited=0
    while [ -z "$PORT_BASE" ]; do
        for base in 20000 30000 40000 50000; do
            exec {PORT_LOCK_FD}>> "$lock_dir/acp-it-ports-$base.lock" || continue
            if flock -n "$PORT_LOCK_FD"; then PORT_BASE=$base; break; fi
            exec {PORT_LOCK_FD}>&-
        done
        if [ -z "$PORT_BASE" ]; then
            [ "$waited" -eq 0 ] && echo "All port blocks are in use by other runs; waiting for one"
            waited=1
            sleep 5
        fi
    done
fi
echo "Port block $PORT_BASE-$((PORT_BASE + 9999))"

# One scenario: its own log directory (logs/<s>/), its own ${TMP}, and a port range no other
# scenario of this run uses (PORT_BASE + 100 * (index mod 100), 100 ports wide).
run_one() { # <scenario> <index> <tee: 0|1>
    local s="$1" idx="$2" from=$((PORT_BASE + ($2 % 100) * 100))
    rm -f "logs/$s/result.txt" "logs/run-all/$s.exit"
    local cmd=(env IT_PORT_RANGE="$from-$((from + 99))"
        timeout 1800 jbang RunScenario.java "$s" --prepared "${PASS_ARGS[@]}")
    if [ "$3" -eq 1 ]; then
        echo
        echo "################ $s"
        "${cmd[@]}" 2>&1 | tee "logs/run-all/$s.console.log"
        echo "${PIPESTATUS[0]}" > "logs/run-all/$s.exit"
    else
        "${cmd[@]}" > "logs/run-all/$s.console.log" 2>&1
        local code=$?
        echo "$code" > "logs/run-all/$s.exit"
        echo "  $(sed -n 's/^status=//p' "logs/$s/result.txt" 2>/dev/null || echo "exit $code") $s" \
            "($(sed -n 's/^seconds=//p' "logs/$s/result.txt" 2>/dev/null || echo '?') s)"
    fi
}

# Phase 2: everything except load-*, JOBS at a time. Phase 3: load-* alone, serially, since they
# measure throughput and thread and heap use.
CONCURRENT=()
SERIAL=()
for i in "${!SCENARIOS[@]}"; do
    case "${SCENARIOS[$i]}" in load-*) SERIAL+=("$i") ;; *) CONCURRENT+=("$i") ;; esac
done
if [ "$JOBS" -gt 1 ] && [ ${#CONCURRENT[@]} -gt 1 ]; then
    echo "Running ${#CONCURRENT[@]} scenario(s), $JOBS at a time; consoles in logs/run-all/<scenario>.console.log"
    for i in "${CONCURRENT[@]}"; do
        while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n; done
        run_one "${SCENARIOS[$i]}" "$i" 0 &
    done
    wait
else
    for i in "${CONCURRENT[@]}"; do run_one "${SCENARIOS[$i]}" "$i" 1; done
fi
for i in "${SERIAL[@]}"; do run_one "${SCENARIOS[$i]}" "$i" 1; done

for s in "${SCENARIOS[@]}"; do
    code="$(cat "logs/run-all/$s.exit" 2>/dev/null || echo '?')"
    if [ -f "logs/$s/result.txt" ]; then
        STATUS[$s]="$(sed -n 's/^status=//p' "logs/$s/result.txt")"
        xfail="$(sed -n 's/^xfail=//p' "logs/$s/result.txt")"
        [ "${STATUS[$s]}" = "PASS" ] && [ "${xfail:-0}" != "0" ] && STATUS[$s]="PASS ($xfail xfail)"
        SECS[$s]="$(sed -n 's/^seconds=//p' "logs/$s/result.txt")"
        NOTES[$s]="$(sed -n 's/^notes=//p' "logs/$s/result.txt")"
        [ "$code" != "0" ] && [ "${STATUS[$s]}" != "FAIL" ] && STATUS[$s]="FAIL (exit $code)"
    else
        STATUS[$s]="FAIL (exit $code, no result)"
        SECS[$s]="-"
        NOTES[$s]=""
    fi
    [[ "${STATUS[$s]}" == PASS* ]] || FAILED=$((FAILED + 1))
done
TOTAL=$(( $(date +%s) - START ))

table() {
    printf '%-32s %-16s %8s  %s\n' "Scenario" "Result" "Time (s)" "Notes"
    printf '%-32s %-16s %8s  %s\n' "--------------------------------" "----------------" "--------" "-----"
    for s in "${SCENARIOS[@]}"; do
        printf '%-32s %-16s %8s  %s\n' "$s" "${STATUS[$s]}" "${SECS[$s]}" "${NOTES[$s]}"
    done
    echo
    echo "${#SCENARIOS[@]} scenarios, $FAILED failed, ${TOTAL} s total (--jobs $JOBS)"
}

echo
echo "========================================================================"
table | tee logs/run-all/summary.txt
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "### Cross-SDK and load scenarios${RUN_LABEL:+ ($RUN_LABEL)}"
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
