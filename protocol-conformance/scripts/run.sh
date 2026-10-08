#!/usr/bin/env bash
# Run the whole-roster audit.
#
#   protocol-conformance/scripts/run.sh --replay <run-id> [--assert-satisfied] [--retain-in-run]   deterministic, no inference
#   protocol-conformance/scripts/run.sh --live --capture <run-id> [--timeout-minutes N]  one real investigative run
#
# A replay without a real recording is REFUSED (exit 2): nothing is fabricated. A live run is a new immutable
# recording under protocol-conformance/runs/<run-id>/ and is never run implicitly; it needs the judging agent's
# own credentials in the environment. --assert-satisfied exits 1 unless the retained conclusion is PASS.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TIER="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$TIER/.." && pwd)"
MODE=""; RUN_ID="${ACP_CONFORMANCE_RUN:-}"; EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --replay) MODE=replay; if [ $# -gt 1 ] && [[ "$2" != --* ]]; then RUN_ID="$2"; shift; fi; shift ;;
    --live) MODE=live; shift ;;
    --capture) RUN_ID="$2"; shift 2 ;;
    --assert-satisfied) EXTRA+=("--assert-satisfied"); shift ;;
    --retain-in-run) EXTRA+=("--retain-in-run"); shift ;;
    --timeout-minutes) EXTRA+=("--timeout-minutes" "$2"); shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 64 ;;
  esac
done
[ -n "$MODE" ] || { echo "choose --replay <run-id> or --live --capture <run-id>" >&2; exit 64; }
[ -n "$RUN_ID" ] || { echo "a run id is required (argument or ACP_CONFORMANCE_RUN)" >&2; exit 64; }
if [ "$MODE" = live ] && [ -d "$TIER/runs/$RUN_ID" ]; then
  echo "runs/$RUN_ID already exists; a live run never replaces a recording" >&2; exit 65
fi
SDK_COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
if [ "$MODE" = live ] && [ -n "$(git -C "$ROOT" status --short --untracked-files=no)" ]; then
  echo "the subject worktree has uncommitted changes; a live run judges a moving target" >&2; exit 66
fi
MVN=("$ROOT/mvnw" -f "$TIER/pom.xml" -q -o)
if [ -n "${ACP_CONFORMANCE_M2:-}" ]; then MVN+=("-Dmaven.repo.local=$ACP_CONFORMANCE_M2"); fi
ARGS=("--$MODE")
if [ "$MODE" = live ]; then ARGS+=("--capture" "$RUN_ID"); else ARGS+=("$RUN_ID"); fi
ARGS+=("--spec-dir" "$TIER/spec" "--runs-dir" "$TIER/runs" "--workspace" "$ROOT" "--sdk-commit" "$SDK_COMMIT" "${EXTRA[@]}")
cd "$TIER"
export ACP_CONFORMANCE_SDK_COMMIT="$SDK_COMMIT"
exec "${MVN[@]}" compile exec:java -Dexec.args="${ARGS[*]}"
