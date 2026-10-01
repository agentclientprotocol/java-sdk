#!/usr/bin/env bash
# Builds the Rust interop programs against the peer checkout under test.
# Launcher contract: integration-testing/README.md, "Contracts". Env: RUST_SDK (the checkout),
# CARGO_TARGET_DIR (per peer ref). Cargo.toml reaches the SDK through .cache/peers/rust-sdk, the
# symlink the runner points at RUST_SDK's ref; a mismatch is an error, not a silent wrong build.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
link="$here/../../.cache/peers/rust-sdk"
if [[ -n "${RUST_SDK:-}" && "$(cd "$link" && pwd -P)" != "$(cd "$RUST_SDK" && pwd -P)" ]]; then
    echo "build.sh: $link does not point at RUST_SDK=$RUST_SDK" >&2
    exit 1
fi
cd "$here"
exec cargo build --release --quiet --bin interop-agent --bin interop-client
