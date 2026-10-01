#!/usr/bin/env bash
# Scans the dependencies the SDK modules ship with against the OSV database (osv.dev).
#
# Maven resolves the tree, so the scan sees exactly what a consumer gets: every reactor module and
# every transitive compile, runtime and provided dependency. Test scope is left out, and so is the
# integration-testing/ harness, which is not a reactor module.
#
# Fails when a finding is HIGH or CRITICAL (CVSS 7.0 or more) or has no severity score yet, unless
# osv-scanner.toml accepts it. Lower findings are reported and do not fail.
#
# Usage: .github/scripts/osv-scan.sh   (OSV_SCANNER overrides the scanner binary)
set -euo pipefail

OSV_SCANNER="${OSV_SCANNER:-osv-scanner}"
CYCLONEDX_VERSION=2.9.3
OUT=target/osv

cd "$(dirname "$0")/../.."

entries=$(grep -c '^\[\[IgnoredVulns\]\]' osv-scanner.toml || true)
for key in id ignoreUntil reason; do
  n=$(grep -c "^${key} *=" osv-scanner.toml || true)
  if [ "$n" != "$entries" ]; then
    echo "osv-scanner.toml: each [[IgnoredVulns]] entry needs id, ignoreUntil and reason" >&2
    exit 2
  fi
done

rm -f target/*-cyclonedx.json
./mvnw -B -q "org.cyclonedx:cyclonedx-maven-plugin:${CYCLONEDX_VERSION}:makeAggregateBom" \
  -DoutputFormat=json -DincludeTestScope=false
mkdir -p "$OUT"
cp target/*-cyclonedx.json "$OUT/bom.cdx.json"

scan() {
  "$OSV_SCANNER" scan source --config osv-scanner.toml --lockfile "$OUT/bom.cdx.json" "$@"
}

# Exit status 1 means vulnerabilities were found; anything above 1 is a scanner failure.
status=0
scan --format json --output-file "$OUT/results.json" || status=$?
if [ "$status" -gt 1 ]; then
  echo "osv-scanner failed with exit status $status" >&2
  exit "$status"
fi
scan --format sarif --output-file "$OUT/results.sarif" || [ $? -eq 1 ]
scan --format markdown --output-file "$OUT/results.md" || [ $? -eq 1 ]

packages=$(jq '[.components[] | select(.type == "library")] | length' "$OUT/bom.cdx.json")
blocking=$(jq -r '
  .results[]?.packages[] | .package as $p | .groups[]
  | select(.max_severity == "" or (.max_severity | tonumber) >= 7.0)
  | "| \($p.name) | \($p.version) | \(.ids | join(", ")) | \(.aliases | map(select(startswith("CVE-"))) | join(", ")) | \(if .max_severity == "" then "unscored" else .max_severity end) |"
' "$OUT/results.json")

{
  echo "## Dependency vulnerability scan"
  echo
  echo "Scanned $packages packages (the SDK modules and their runtime dependencies, test scope excluded) against OSV."
  if [ "$entries" -gt 0 ]; then
    echo "$entries accepted in osv-scanner.toml until their ignoreUntil date."
  fi
  echo
  if [ -n "$blocking" ]; then
    echo "**FAILED: HIGH, CRITICAL or unscored vulnerabilities found.**"
    echo
    echo "| Package | Version | Advisory | CVE | CVSS |"
    echo "|---|---|---|---|---|"
    echo "$blocking"
    echo
  elif [ "$status" -eq 1 ]; then
    echo "Passed: only findings below HIGH, listed below."
    echo
  else
    echo "Passed: no known vulnerabilities."
    echo
  fi
  if [ "$status" -eq 1 ]; then
    cat "$OUT/results.md"
  fi
} | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"

[ -z "$blocking" ]
