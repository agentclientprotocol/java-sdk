#!/usr/bin/env bash
# The raw conformance driver is Python 3 standard library only: nothing to build.
# Launcher contract: integration-testing/README.md, "Contracts".
set -euo pipefail
command -v python3 > /dev/null || { echo "raw: python3 is required" >&2; exit 1; }
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' || { echo "raw: python3 >= 3.8 is required" >&2; exit 1; }
