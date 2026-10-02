#!/usr/bin/env bash
#
# Print "sha=<commit>" for a peer SDK at the ref a run will test, for CI cache keys:
#
#   integration-testing/scripts/peer-sha.sh <peer> <peers-ref> "<peer overrides>"
#
# <peer overrides> is the space-separated "<peer>=<ref>" list run-all.sh takes as --peer; the
# peer's entry wins over <peers-ref>, and an empty <peers-ref> means the ref in peers.json.
# A ref that git ls-remote cannot resolve (a SHA, say) is
# printed as given.
set -euo pipefail
peer="$1" ref="$2" overrides="${3:-}"
for o in $overrides; do
    case "$o" in "$peer="*) ref="${o#*=}" ;; esac
done
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
url="$(jq -r --arg n "$peer" '.[$n].url // empty' "$here/peers.json")"
[ -n "$url" ] || { echo "unknown peer $peer (see peers.json)" >&2; exit 2; }
[ -n "$ref" ] || ref="$(jq -r --arg n "$peer" '.[$n].ref // "main"' "$here/peers.json")"
sha="$(git ls-remote "$url" "refs/heads/$ref" "refs/tags/$ref" | head -1 | cut -f1)"
echo "peer $peer at $ref: ${sha:-$ref}" >&2
echo "sha=${sha:-$ref}"
