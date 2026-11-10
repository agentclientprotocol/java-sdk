#!/usr/bin/env bash
# The TypeScript interop programs are plain ES modules: nothing to compile. Checks that TS_SDK is a
# built typescript-sdk checkout (peers.json builds it: npm ci && npm run build): every module
# interop/sdk.mjs loads through the package's "exports" is there, and so is the `ws` package the
# programs load from its node_modules. Launcher contract: integration-testing/README.md,
# "Contracts". Env: TS_SDK.
set -euo pipefail
: "${TS_SDK:?TS_SDK is not set}"
command -v node >/dev/null || { echo "build.sh: node is not on the PATH" >&2; exit 1; }
[ -e "$TS_SDK/node_modules/ws/package.json" ] || { echo "build.sh: $TS_SDK/node_modules/ws is missing: is TS_SDK a built typescript-sdk checkout?" >&2; exit 1; }
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TS_SDK="$TS_SDK" node --input-type=module -e "
const m = await import('$here/interop/sdk.mjs');
const missing = ['.', './experimental/http-client', './experimental/ws-client', './experimental/server', './experimental/node']
  .map((s) => [s, m.exportedFile(s)]).filter(([, f]) => !f);
if (missing.length) { console.error('build.sh: the SDK exports none of', missing.map(([s]) => s).join(', ')); process.exit(1); }
" || { echo "build.sh: $TS_SDK is not a built typescript-sdk checkout (see the error above)" >&2; exit 1; }
