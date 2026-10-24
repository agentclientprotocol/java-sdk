# Auditing the ACP Java SDK against the whole stable ACP v1 specification

- **Subject:** ACP Java SDK, released 0.80.0 (`5bb2cf6`), then the fixes released in 0.81.0
- **Specification:** stable ACP v1 at `agentclientprotocol/agent-client-protocol@2797d331` (schema `schema-v1.24.1`)
- **Runs:** 2026-10-08. **Decisions on the open claims:** 2026-10-24
- **Results:** [run 001](results/run-001.md) and [run 002](results/run-002.md), one row per requirement

## 1. Summary

We turned the whole stable ACP v1 specification into 240 RFC 2119 requirements. Each one has its own
id, quotes the official pages word for word, and is tied to the exact source bytes by hash. Then we
asked one investigative AI judge a single question: *does this exact SDK satisfy every applicable
requirement?* The judge read the code, ran the SDK's own tests, and answered each of the 240 items
once, in 27 minutes.

| Outcome of run 001 (the code released as 0.80.0) | Count |
|---|---|
| PASS | 193 |
| FAIL | 6 |
| CANNOT_DETERMINE (abstained) | 39 |
| NOT_APPLICABLE (the item's stated condition does not hold) | 2 |
| Protocol errors (malformed, missing or duplicate answers) | 0 |
| **Conclusion** | **FAIL** |

Two independent reviews by other AI coding agents confirmed all six FAILs. A failing test then
reproduced each one against the SDK, and each was fixed; 20 new tests cover the fixes. A second
run judged all six PASS. It also failed three items the first run had abstained on. We fixed two
of those, and declared the third the application's responsibility (section 7). Everything here
ships in **0.81.0**.

## 2. Why a whole-spec audit, and why one vote

A conformance test suite checks what someone remembered to write a test for. A specification has a
denominator: the set of things it requires. We fixed that denominator *before* any model was asked
anything. So the model could never choose which requirements to answer, and "193 of 240" means
something.

That gave the setup its shape:

- a frozen roster of requirements (the denominator);
- one jury (`Rfc2119Jury` from Agent Eval) that gives the complete roster to one investigative
  agent, once;
- a parser that maps each line of the answer to exactly one roster item, and treats anything else
  as a failure of the instrument;
- a verdict computed in Java from the per-item answers.

The model assesses. It never decides what the set of assessments means.

## 3. Building the denominator

- **The sources are kept byte for byte.** That means the 21 stable v1 pages, the stable schema, the
  licence, and the JSON-RPC 2.0 page that ACP incorporates by reference, each with its SHA-256 sum,
  under [`spec/official/`](spec/official).
- **Every keyword occurrence has a disposition.** There are 188 `MUST`/`SHOULD`/`MAY` occurrences
  in the prose pages. Each one is inside an imported clause, is a declared duplicate of another item,
  or is one of three explicit non-normative cases. The 146 keyword occurrences in the schema's
  descriptions are accounted for by JSON Pointer. The generated schema page was checked line by line
  and is a mirror of the schema.
- **Nothing is abbreviated or invented.** Each clause keeps its original wording. A deterministic
  importer finds every clause verbatim in the retained bytes, and fails on any drift. The schema's
  structural constraints (required members, discriminated unions, enum values) enter as 31 labelled
  derivations, and 18 JSON-RPC clauses enter as incorporated requirements. 26 sentences with a
  lowercase "must" were reviewed one by one; 5 were imported, each with its reason recorded.
- **The semantics stay honest.** Each keyword keeps its original strength. The only normalizations
  are `REQUIRED`→`MUST` and `OPTIONAL`→`MAY`, and the originals are kept. 116 items carry an
  explicit applicability condition; only those may be judged not applicable. Each item names its
  role (agent, client or both). It also says who can satisfy it: the SDK alone (158 items), the SDK
  and the application together (39), or the application alone, with the SDK judged on what it
  exposes (43).

Result: **N = 240** (MUST 120, MUST NOT 30, SHOULD 52, SHOULD NOT 4, MAY 34), roster revision
`acp-v1-2797d331-r1`. The roster is regenerated deterministically from a reviewed manifest: see
[`spec/requirements.md`](spec/requirements.md) and the [README](README.md).

## 4. The harness and the run

The harness is a standalone Java 21 project in this directory, outside the SDK's Java 17 build. It
never reaches the published artifacts.

- It loads the roster and refuses to run if any digest has drifted.
- It puts an ACP-specific instruction in front of the jury's generated prompt.
- It runs either a replay (no inference) or a live run (an agent scoped to the pinned workspace).

Offline tests over the real roster with synthetic answers pin the semantics:

- the whole roster reaches one execution unchanged;
- abstentions count as abstentions;
- malformed, missing, duplicate or undeclared answers are instrument errors, never findings about
  the SDK;
- a replay without a genuine recording is refused.

Run `acp-v1-2797d331-r1-5bb2cf6-001`:

- the request was 191,689 characters;
- the judge was `claude-fable-5-1`, for 27 minutes and 179 turns;
- it ran 85 shell commands, including the SDK's own test suite (467 tests, green).

We learned two things about the instrument:

- The jury's 1 MiB result bound cannot hold a verdict for 240 full-text items. Verdicts are now kept
  in parts that reassemble, and the reassembly is proven equal to the whole.
- Its in-memory capture bound refused the agent's 3 MB session after the agent had answered. The
  response was recovered verbatim, with provenance, from the CLI's own session log. Capture now
  writes to a file.

## 5. The six findings

All six are the same kind of gap: an obligation of the client or the agent that the SDK's Javadoc
explicitly left to the application ("the SDK does not check …"). The specification binds the
complete client or agent, and the SDK owns the send path, so the SDK is the right place to enforce
them. Each was reproduced by a failing test, then fixed.

| # | Requirement (verbatim source) | What the SDK did | Fix (0.81.0) |
|---|---|---|---|
| 1 | initialization.mdx:98: if the Client does not support the version the Agent answers, the Client **SHOULD** close the connection | stored the capabilities and never compared the version | `initialize` fails with `AcpVersionException` and closes the connection |
| 2 | authentication.mdx:153: Clients **MUST NOT** pass a `terminal` method to `authenticate` | sent any method id | refuses a terminal method id and sends nothing |
| 3 | session-setup.mdx:522: Clients **MUST** verify the Agent's capabilities before using HTTP or SSE MCP transports | sent HTTP and SSE servers without checking `mcpCapabilities` | `AcpCapabilityException` on new, load, resume and fork unless the agent advertised them |
| 4 | prompt-turn.mdx:114: Clients **MUST** restrict content types to the negotiated prompt capabilities | `requireImageContent()` existed, but no send path called it | `prompt` refuses image, audio and embedded blocks unless the agent advertised them |
| 5 | prompt-turn.mdx:350: the Client **MUST** respond to all pending `session/request_permission` requests with `cancelled` | `session/cancel` only sent the notification, so a pending permission request stayed pending forever | the client answers them `cancelled` and stops the handler |
| 6 | session-config-options.mdx:187: Agents **MUST NOT** send `boolean` config options unless the Client advertised support | sent whatever the handler returned | omitted with a warning unless the client advertised support |

Both reviewers would have preferred rejecting to omitting for finding 6. We kept omission, because
the specification itself names it as the fallback (session-config-options.mdx:188) and a
notification has no error path.

Finding 5 is the most interesting.

- **The scene.** An agent asks the user "may I delete these files?", the user presses *stop*
  instead, and the client sends `session/cancel`.
- **What must happen.** The Agent must finish aborting and answer the prompt with stop reason
  `cancelled`. It cannot finish while a permission request is still open, so the Client must answer
  every pending one.
- **What the SDK did.** It tracked every inbound request, so it could answer `$/cancel_request`.
  An outgoing `session/cancel`, however, triggered none of that. If the application's permission
  handler was waiting on a dialog the user had just abandoned, the agent waited forever and the
  prompt never returned.
- **Why tests missed it.** Each side honours its own contract, and the hang lives in the gap between
  them. Only reading the specification whole, as a denominator, surfaces it.

The 39 abstentions are nearly all one case: "the SDK transports this; the behaviour belongs to the
application". That covers path semantics, the elicitation UI, the content of plans, lists and config
options, and the terminal and file-system handlers the SDK does not ship. They are kept item by item
with their reasons; they are not failures, and they are not hidden in the aggregate.

## 6. After the fixes: run 002

| Status | Run 001 (`5bb2cf6`) | Run 002 (`44f2a09`) |
|---|---|---|
| PASS | 193 | **209** |
| FAIL | 6 | 3 |
| CANNOT_DETERMINE | 39 | 24 |
| NOT_APPLICABLE | 2 | 3 |
| Unanswered (instrument error) | 0 | 1 |
| Conclusion | FAIL | FAIL |

All six fixed items are PASS, each with a citation to the new code, and ten former abstentions
became PASS. The subject `44f2a09` is the head of the `protocol-conformance` branch where the fixes
were made; on `main` the same changes have other commit ids.

Three items that run 001 had abstained on became FAIL:

- the client forwards the application's `cwd` and `additionalDirectories` without checking that
  they are absolute;
- the agent neither generates nor checks the uniqueness of outstanding URL `elicitationId`s.

One item, `ACP-V1-OVERVIEW-ABSOLUTE-PATHS` ("All file paths in the protocol MUST be absolute"), is
recorded as unanswered. The judge's answer did not start a line, so the parser did not take it. The
answer's text reads FAIL. It is listed as an open item in section 7, and never counted as a
finding or a pass.

## 7. Decisions on the open items (maintainer, 2026-10-24)

| Requirement | Decision | In 0.81.0 |
|---|---|---|
| `ACP-V1-SETUP-CWD-ABSOLUTE` (session-setup.mdx:362) | **Fixed.** A failing test reproduced it first. The client refuses a `session/new`, `load`, `resume` or `fork` whose `cwd` is not absolute, with `IllegalArgumentException`, and sends nothing. The check uses the path's form, POSIX (`/…`) or Windows (`C:\…`, `\\server\share…`), not `Path.isAbsolute()`, because the agent may run on another host and operating system. | yes |
| `ACP-V1-SETUP-ADDITIONAL-DIRS-ABSOLUTE` (session-setup.mdx:340) | **Fixed** with the same check, for every `additionalDirectories` entry. | yes |
| `ACP-V1-ELIC-AGENT-UNIQUE-ELICITATION-ID` (elicitation.mdx:158) | **Application responsibility.** The application chooses the id and alone knows when a URL elicitation stops being outstanding, because completion is optional. The roster moves the item to `application` in revision `acp-v1-2797d331-r2`. A narrow SDK guard, which refuses a duplicate id while its create request is in flight, is planned for a later release. | roster r2 |
| Boolean config options (finding 6): omit or reject | **Omit with a warning** (unchanged), as the specification names. | yes |
| `ACP-V1-OVERVIEW-ABSOLUTE-PATHS` | **Open.** The `cwd` and `additionalDirectories` fix covers part of it. Other paths the SDK forwards (file-system requests, terminal `cwd`, tool-call locations) are still not checked. To be judged in the next run. | partly |

A recording replays only against the roster it was made with. Runs 001 and 002 were made at
revision `-r1`, and the roster is now `-r2`; the next live run will be the first at `-r2`.

## 8. What this is and is not

This is a model-assisted audit of a defined subject against a frozen, reviewed roster, with every
judgment kept item by item. It is not official ACP certification. The protocol's authors have not
reviewed the roster, and a judge's PASS is support, not proof. We call only the six findings of run
001 and the two path findings of run 002 bugs, and only because a failing test reproduced each one.

## What is published

- The roster and its sources: [`spec/`](spec), regenerated and checked with
  `python3 -I protocol-conformance/scripts/import-spec.py check`.
- The harness: [`src/`](src), [`scripts/`](scripts) and the [README](README.md).
- One page per run, with every item's recorded status and the judge's reason:
  [run 001](results/run-001.md) and [run 002](results/run-002.md).
- The reproduction tests in the SDK: `acp-core/src/test/java/com/agentclientprotocol/sdk/spec/`
  (`ClientCapabilityConformanceTest`, `CancelAnswersPendingPermissionsTest`,
  `BooleanConfigOptionGatingTest`, `SessionPathConformanceTest`).

The raw recordings (request, response, session transcripts and verdict parts, about 16 MB) are not
published. The session transcripts carry the environment of the machine the judge ran on. The
recorded status of every item, and the judge's reason for it, are on the result pages above.
