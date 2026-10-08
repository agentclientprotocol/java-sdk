# Retained official sources

Exact bytes, retained unchanged, so that every derived requirement can point at a hash-verified
origin. `SHA256SUMS` lists every file; `../../scripts/import-spec.py check` verifies it on every run.

## agent-client-protocol/

Copied with `git show <commit>:<path>` from `agentclientprotocol/agent-client-protocol` at commit
`2797d33125c14e5bcfc815952930130c43e5bc4c` (fetched `origin/main` on 2026-10-08). Licensed under the
Apache License 2.0; the repository's `LICENSE` is retained verbatim alongside. The upstream repository
has no `NOTICE` file at this commit.

- `docs/docs.json` — the site navigation; evidence of which pages form the stable v1 group (tag
  "Latest") and which are the hidden draft group.
- `docs/protocol/v1/*.mdx` — the 21 top-level stable v1 pages (including the generated `schema.mdx`).
  Draft pages under `docs/protocol/v1/draft/` and all of `docs/protocol/v2/` are deliberately not
  retained; see `spec/manifest/scope.toml` for the exclusion record.
- `schema/v1/schema.json` — the stable schema. SHA-256
  `3c17bd6385d90cf672d8a661fddc359d73422cf8b8ce6865213d25cfd4c0eca7`, byte-identical at this commit, at
  release tags `schema-v1.24.1` (`1761180e`), `schema-v1.24.0`, `schema-v1.23.0`, Rust `v1.9.1`, and in
  this SDK's vendored `acp-core/src/test/resources/schema/v1/schema.json`.
- `schema/v1/meta.json` — the stable method-name table (protocol version 1).
- `schema/v1/CHANGELOG.md` — release history of the schema crate, for version identity only.

## jsonrpc/

`specification.html` is the JSON-RPC 2.0 specification page as served by
`https://www.jsonrpc.org/specification` on 2026-10-08 (document header: "Origin Date: 2010-03-26 (based
on the 2009-05-24 version); Updated: 2013-01-04"). SHA-256
`8fe1edfdca511d309e712e47447457ea5159b728ec02071a84593aed692aefeb`. ACP incorporates JSON-RPC 2.0 by
reference (`overview.mdx` lines 10, 219 and 227; `extensibility.mdx` line 49). The page carries its own
copyright notice from the JSON-RPC Working Group permitting copying and distribution in whole or in part
provided that notice is included; it is retained here unmodified for that reason.

## What this directory is not

It is not a modified or republished specification. The derived roster lives in `../requirements.json`
and is labelled "derived ACP v1 testing requirements"; the authority remains the retained bytes.
