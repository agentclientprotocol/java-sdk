#!/usr/bin/env bash
# Prepare the protocol-conformance tier for offline use: verify the frozen roster, resolve the harness
# dependencies into the selected Maven repository, and record the exact producer identity.
#
#   protocol-conformance/scripts/prepare.sh [--producer-repo DIR]
#
# Environment:
#   ACP_CONFORMANCE_M2   alternate Maven repository (passed as -Dmaven.repo.local; wrapper 3.8.6 does not
#                        honour MAVEN_ARGS). Defaults to the ordinary ~/.m2.
#   ACP_CONFORMANCE_M2 is also what run.sh uses, so child builds and the harness share one repository.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TIER="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$TIER/.." && pwd)"
PRODUCER_REPO="${AGENT_JUDGE_REPO:-$HOME/projects/agent-judge}"
while [ $# -gt 0 ]; do
  case "$1" in
    --producer-repo) PRODUCER_REPO="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 64 ;;
  esac
done
MVN=("$ROOT/mvnw" -f "$TIER/pom.xml" -q)
if [ -n "${ACP_CONFORMANCE_M2:-}" ]; then
  MVN+=("-Dmaven.repo.local=$ACP_CONFORMANCE_M2")
fi

echo "== Java"
JAVA_VERSION="$(java -version 2>&1 | head -1 | tr -d '"')"
echo "   $JAVA_VERSION"
case "$JAVA_VERSION" in *' 21.'*|*' 22.'*|*' 23.'*|*' 24.'*|*' 25.'*) ;; *) echo "   Agent Eval needs Java 21+; found: $JAVA_VERSION" >&2; exit 65 ;; esac

echo "== Roster"
python3 -I "$TIER/scripts/import-spec.py" check

echo "== Producer source"
if [ -d "$PRODUCER_REPO/.git" ]; then
  PRODUCER_SHA="$(git -C "$PRODUCER_REPO" rev-parse HEAD)"
  PRODUCER_DIRTY="$(git -C "$PRODUCER_REPO" status --short | wc -l | tr -d ' ')"
  echo "   $PRODUCER_REPO @ $PRODUCER_SHA (dirty files: $PRODUCER_DIRTY)"
else
  PRODUCER_SHA="unknown"; PRODUCER_DIRTY="unknown"
  echo "   no producer checkout at $PRODUCER_REPO (set AGENT_JUDGE_REPO); snapshot identity recorded by digest only"
fi

echo "== Dependencies"
"${MVN[@]}" -o dependency:resolve dependency:resolve-plugins >/dev/null 2>&1 || {
  echo "   offline resolution failed; resolving online once"; "${MVN[@]}" dependency:resolve dependency:resolve-plugins >/dev/null; }
mkdir -p "$TIER/target"
"${MVN[@]}" -o dependency:build-classpath -Dmdep.outputFile="$TIER/target/classpath.txt" >/dev/null
IDENTITY="$TIER/target/prepare-identity.json"
{
  echo "{"
  echo "  \"prepared\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
  echo "  \"java\": \"$JAVA_VERSION\","
  echo "  \"producerRepo\": \"$PRODUCER_REPO\","
  echo "  \"producerSourceSha\": \"$PRODUCER_SHA\","
  echo "  \"producerDirtyFiles\": \"$PRODUCER_DIRTY\","
  echo "  \"mavenRepoLocal\": \"${ACP_CONFORMANCE_M2:-$HOME/.m2/repository}\","
  echo "  \"rosterSha256\": \"$(cut -d' ' -f1 "$TIER/spec/requirements.sha256")\","
  echo "  \"jars\": ["
  first=1
  tr ':' '\n' < "$TIER/target/classpath.txt" | grep -E 'agent-judge|agent-client|agent-claude|claude-code-sdk' | while read -r jar; do
    [ -f "$jar" ] || continue
    sum="$(sha256sum "$jar" | cut -d' ' -f1)"
    if [ $first -eq 1 ]; then first=0; else echo ","; fi
    printf '    {"path": "%s", "sha256": "%s"}' "$jar" "$sum"
  done
  echo
  echo "  ]"
  echo "}"
} > "$IDENTITY"
echo "   recorded $IDENTITY"
grep -c '"sha256"' "$IDENTITY" | sed 's/^/   producer and bridge jars digested: /'
echo "== Compile and offline harness tests"
"${MVN[@]}" -o test
echo "prepared."
