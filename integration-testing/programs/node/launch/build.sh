#!/usr/bin/env bash
# The TypeScript interop programs are plain ES modules: nothing to compile. Checks that TS_SDK is a
# built typescript-sdk checkout (peers.json builds it: npm ci && npm run build) with the `ws`
# package the programs load from its node_modules. Launcher contract: integration-testing/README.md,
# "Contracts". Env: TS_SDK.
set -euo pipefail
: "${TS_SDK:?TS_SDK is not set}"
for f in dist/acp.js dist/http-stream.js dist/ws-stream.js dist/server.js dist/node-adapter.js node_modules/ws/package.json; do
    [ -e "$TS_SDK/$f" ] || { echo "build.sh: $TS_SDK/$f is missing: is TS_SDK a built typescript-sdk checkout?" >&2; exit 1; }
done
command -v node >/dev/null || { echo "build.sh: node is not on the PATH" >&2; exit 1; }
