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

## The harness

| Path | Responsibility |
|---|---|
| `pom.xml` | Standalone Java 21 project (Agent Eval `0.18.0-SNAPSHOT` from producer `b7d2d88`, Agent Client 0.30.0); no deploy |
| `AcpRequirementRoster` | Loads `spec/requirements.json`, verifies the roster digest sidecar and every retained source digest, constructs full-text native `Rfc2119Requirement`s |
| `AcpAuditContext` | Prepends the ACP audit instruction to the producer's generated roster prompt; delegates `execute()` so native facts survive |
| `AcpJudgeBackends` | Replay (default, zero inference) or live (`AgentClientEvalModel` over a workspace-scoped `AgentClient`), with capture |
| `AcpRunCapture` / `AcpRecordedResponse` | Immutable run directories; replay pairs a recording only with the identical regenerated request |
| `AcpProtocolConformanceDemo` | Builds the jury publicly, calls `vote()` once, retains the complete verdict, prints a summary |
| `RetainedVerdicts` | Complete V6 retention: one document, or V6 parts under the producer's 1 MiB bound, reopened and reassembled equal |
| `ProducerIdentity` | Location and SHA-256 of the producer and bridge JARs actually loaded |

```bash
protocol-conformance/scripts/prepare.sh                      # roster check, dependencies, identity, offline tests
protocol-conformance/scripts/run.sh --replay <run-id>        # deterministic replay; refused (exit 2) without a real recording
protocol-conformance/scripts/run.sh --replay <run-id> --assert-satisfied
protocol-conformance/scripts/run.sh --live --capture <run-id>   # one explicitly approved investigative run
./mvnw -f protocol-conformance/pom.xml -o test               # offline harness tests over synthetic answers
./mvnw -f protocol-conformance/pom.xml -o test -Dacp.conformance.run=<run-id>                     # replay regression
./mvnw -f protocol-conformance/pom.xml -o test -Dacp.conformance.run=<run-id> -Dacp.conformance.assert=true  # acceptance
```

Response semantics the offline tests pin: every requirement reaches one backend execution unchanged;
`CANNOT_DETERMINE` is an abstention; `NOT_APPLICABLE` is accepted only on a requirement with a
declared condition; malformed, duplicate, missing or undeclared answers are instrument errors, never
SDK findings; a refused replay or a crashed backend is retained as a failed run.

**Retention under the producer's bound.** The producer bounds one V6 document at 1 MiB and refuses
rather than truncates; a 240-item full-text verdict is about 1.7 MB. `RetainedVerdicts` therefore
writes the verdict as V6 *parts* under `verdict-v6-parts/`: `root.json` carries the collective
judgment and each `part-NN.json` is a complete V6 roster verdict over a contiguous slice of the
items, every file under the bound, with `manifest.json` recording slices and digests. `read` reopens
every part through the producer's codec and reassembles a Verdict that equals the one voted
(asserted by the offline tests). Small verdicts stay one `verdict-v6.json`.

## Status

Checkpoints A (freeze and import) and B (wire and falsify offline) are complete. No live run has been
made, so no conformance judgment exists and `runs/` holds no recording. The roster has not been
reviewed by the protocol authors and makes no claim of official ACP certification.
