# Plan: Streamable HTTP Agent Transport

> **Status**: Milestone one implemented  
> **Created**: 2026-05-18  
> **Primary goal**: Java agents can be served from a running Java web server over the Streamable HTTP transport while preserving current WebSocket behavior until parity is proven.

## Goal

Add an agent-side Streamable HTTP transport backed by Jetty, with:

- one fresh ACP agent runtime per accepted remote connection
- a public `AcpAgentFactory` seam for listener-backed transports
- RFD-oriented HTTP + SSE behavior
- strict and compatible routing modes
- a fixture-driven conformance harness that exercises a real running Java listener

## Public Shape

- Add `AcpAgentFactory` in `acp-core`.
- Make the async seam explicit:
  - `AcpAgentFactory.async(...)`
  - `AcpAgentFactory.sync(...)`
- Add `StreamableHttpAcpAgentTransport` in a dedicated Jetty adapter module:
  - `acp-streamable-http-jetty`
- Keep the current WebSocket single-agent API intact in this milestone.
- PLAN: once Streamable HTTP reaches behavioral parity, migrate remote WebSocket handling toward the same factory-backed listener model.

## Runtime Model

```text
StreamableHttpAcpAgentTransport
  accepts remote connection
    -> AcpAgentFactory creates fresh agent runtime
    -> per-connection AcpAgentTransport drives that runtime
    -> one ACP connection contains many logical ACP sessionIds
```

- `Acp-Connection-Id` identifies one remote peer relationship.
- `Acp-Session-Id` identifies one logical ACP session inside that connection.
- The transport owns routing; the agent owns protocol meaning.

## Routing / Lifecycle Decisions

- `initialize`
  - creates a provisional connection
  - starts a fresh agent runtime
  - returns `200 OK` with JSON-RPC response + `Acp-Connection-Id`
  - publishes the connection only after successful initialize
- non-initialize POSTs require `Acp-Connection-Id`
- connection-scoped SSE streams carry:
  - initialize follow-up traffic
  - responses to `session/new`
  - responses to `session/load`
- session-scoped SSE streams carry:
  - responses to ordinary session-scoped requests
  - session updates
  - agent-originated session-scoped requests such as permission prompts
- DELETE tears down the connection and releases transport state.

### Routing ledgers

- client request id -> expected outbound response scope
- agent request id -> expected client response scope

Wrong-stream client replies are protocol errors. Unknown response ids preserve current SDK parity and are allowed through for the session layer to decide.

### Strict vs compatible

- `STRICT`
  - rejects unknown methods without explicit routing
  - rejects unknown session stream opens with `404`
- `COMPATIBLE`
  - falls back to `params.sessionId` inference for unknown methods
  - permits provisional session streams before `session/load`

## Known RFD Gap

The RFD says unknown session-scoped GETs should return `404`, but the resume flow also asks clients to open the session SSE stream before sending `session/load`. This transport keeps that tension explicit:

- strict mode preserves the literal 404 rule
- compatible mode creates a provisional `PENDING_LOAD` session stream

PLAN: revisit this once the protocol resolves the resume/session-load ordering contract more precisely.

## Test Harness

Create an in-repo fixture:

```text
test-fixtures/streamable-http-client/
```

The fixture is:

- TypeScript
- HTTP-only
- scenario-driven
- the single owner of canonical transcript serialization
- run against a real Java Jetty listener

Covered scenarios:

- happy path
- permission round-trip
- session load / provisional pre-open
- two logical sessions
- wrong-stream response rejection
- validation failures

The Java module also keeps focused integration coverage for strict unknown-session behavior.

## PLAN / Follow-Up Work

- extract a shared remote-core layer only after HTTP parity is proven
- migrate WebSocket toward the same factory-backed listener model
- add idle/provisional-session eviction and replay retention policies
- revisit per-logical-session active-prompt tracking in `AcpAgentSession`
- expose richer diagnostics / observability hooks
- decide whether compatible provisional session streams remain necessary after the RFD is clarified
