# protocol-conformance

Whole-specification RFC 2119 conformance audit of this SDK against the stable ACP v1 specification.
This directory is outside the Maven reactor and outside the shipped artifacts; nothing in `acp-*`
depends on it.

## What is here now

| Path | Contents |
|---|---|
| `spec/official/` | Exact pinned upstream bytes (21 stable v1 pages, stable schema, licence, JSON-RPC 2.0 page) with `SHA256SUMS` and `PROVENANCE.md` |
| `spec/manifest/` | The reviewed manifest: scope, and one TOML file per source with every requirement, duplicate, non-normative keyword occurrence and reviewed prose sentence |
| `spec/requirements.json` | Generated roster consumed by the Java harness — 240 requirements at roster revision `acp-v1-2797d331-r1` |
| `spec/requirements.md` | The same roster as a readable table |
| `spec/coverage-ledger.md` | Every RFC 2119 keyword occurrence, schema description, JSON-RPC section and reviewed prose sentence with its single disposition |
| `scripts/import-spec.py` | Deterministic retain / check / write tool (Python 3.11+, standard library only) |

The roster is labelled *derived ACP v1 testing requirements*. It is not a replacement specification:
every imported clause is the original wording, located verbatim in the retained bytes, with its
original keyword strength (only the RFC 2119 synonyms `REQUIRED`→`MUST` and `OPTIONAL`→`MAY` are
normalized, and the original is kept beside the normalized form).

## Regenerating and checking

```bash
python3 -I protocol-conformance/scripts/import-spec.py check   # hashes, manifest, coverage, rendered files current
python3 -I protocol-conformance/scripts/import-spec.py write   # re-render after editing the manifest
```

`check` fails if any retained byte changed, any clause is not found verbatim in its declared line
range, any keyword occurrence in a selected page is unclaimed, any `schema.mdx` keyword line is not
mirrored from a `schema.json` description, or the rendered files are stale. Re-retaining from a local
clone of the protocol repository:

```bash
python3 -I protocol-conformance/scripts/import-spec.py retain \
  --protocol-repo /path/to/agent-client-protocol --jsonrpc-html /path/to/specification.html
```

## How the roster is built

- **Scope**: the 21 pages in the `docs/docs.json` navigation group "v1" (tag "Latest"), the stable
  `schema/v1/schema.json`, and the JSON-RPC 2.0 clauses ACP incorporates by reference. Draft pages,
  v2, the unstable schema and RFDs are excluded (reasons in `spec/manifest/scope.toml`). The stable
  transports page still calls Streamable HTTP a draft proposal, so this SDK's HTTP and WebSocket
  transports are assessed only under the stable *custom transport* clauses.
- **Keyword occurrences**: every `MUST`, `MUST NOT`, `SHOULD`, `SHOULD NOT`, `MAY`, `REQUIRED`,
  `OPTIONAL`, `SHALL`, `RECOMMENDED` occurrence in the prose pages (188) is either inside an imported
  clause, a declared duplicate of a canonical requirement, or an explicit non-normative disposition.
- **Schema**: the 146 keyword occurrences in `schema.json` descriptions are dispositioned by JSON
  Pointer; `schema.mdx` (181 occurrences) is proven line by line to be a mirror of those descriptions.
  Structural constraints (required members, discriminated unions, enum values) are imported as
  labelled *derivations* whose member lists are generated from the schema bytes.
- **Prose**: lowercase "must"/"should" sentences were reviewed one by one; five are imported at a
  declared strength with the reason recorded, the rest are marked covered or non-normative.
- **Roles and responsibility**: each requirement declares `agent`, `client` or `both`, and whether
  the SDK alone can satisfy it (`sdk`), the SDK and the embedding application together (`shared`),
  or the application alone, with the SDK assessed on what it exposes (`application`). Capability- and
  lifecycle-conditional clauses carry an explicit `applicability` condition; only those may be judged
  not applicable.

## Status

Checkpoint A (freeze and import) of the plan is complete. The Java 21 harness, the single
investigative `Rfc2119Jury` run and offline replay are later checkpoints and are not yet present; no
conformance judgment has been made. The roster has not been reviewed by the protocol authors and
makes no claim of official ACP certification.
