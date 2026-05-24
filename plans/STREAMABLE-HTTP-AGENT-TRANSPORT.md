# Plan: Streamable HTTP Agent Transport

> **Status**: Milestone one implemented  
> **Created**: 2026-05-18  
> **Primary goal**: Java agents can be served from a running Java web server over the Streamable HTTP transport, including WebSocket upgrades on the same ACP endpoint.

## Goal

Add an agent-side Streamable HTTP transport backed by Jetty, with:

- one fresh ACP agent runtime per accepted remote connection
- a public `AcpAgentFactory` seam for listener-backed transports
- RFD-oriented HTTP + SSE behavior
- WebSocket upgrade handling on the same ACP path
- strict and compatible routing modes
- a fixture-driven conformance harness that exercises a real running Java listener

## Public Shape

- Add `AcpAgentFactory` in `acp-core`.
- Make the async seam explicit:
  - `AcpAgentFactory.async(...)`
  - `AcpAgentFactory.sync(...)`
- Add `StreamableHttpAcpAgentTransport` in a dedicated Jetty adapter module:
  - `acp-streamable-http-jetty`
- Keep the current legacy WebSocket single-agent API intact in `acp-websocket-jetty`.
- Serve the RFD-compatible remote WebSocket upgrade path from `StreamableHttpAcpAgentTransport`, matching the Rust SDK shape where one HTTP server owns POST/SSE and WebSocket upgrades.

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
- WebSocket upgrades:
  - create one connection during the upgrade handshake
  - return `Acp-Connection-Id` on the `101 Switching Protocols` response
  - require the first client-originated JSON-RPC message on the socket to be `initialize`
  - exchange JSON-RPC messages as text frames until the socket closes

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

Use Java integration tests only for this branch so the PR stays focused on SDK
transport behavior instead of adding a separate fixture harness.

Covered scenarios:

- happy path over Streamable HTTP POST + SSE
- permission round-trip
- session load / provisional pre-open
- two logical sessions
- wrong-stream response rejection
- validation failures
- strict unknown-session behavior
- WebSocket upgrade behavior on the same Java listener

## Demo Server

Add a runnable Java demo server at:

```text
test-fixtures/streamable-http-agent-server/
```

It packages a small echo-style ACP agent into a runnable jar backed by the real
Jetty `StreamableHttpAcpAgentTransport`, so manual testing can exercise a live
HTTP/SSE endpoint and WebSocket upgrade endpoint instead of only the
integration-test fixture lifecycle.

## PLAN / Follow-Up Work

- extract a shared remote-core layer only after HTTP parity is proven.
  Here, "remote-core" means the transport-independent runtime machinery that
  both remote listener transports need: per-connection agent factory creation,
  connection/session registries, lifecycle teardown, request/response routing
  ledgers, timeout/error propagation, and observability hooks. The actual wire
  adapters should remain transport-specific: legacy WebSocket framing stays in
  the WebSocket module, while the RFD Streamable HTTP endpoint owns HTTP
  methods, headers, SSE parsing, status codes, and its WebSocket upgrade branch.
  Deferring this extraction keeps the first implementation close to the RFD and
  avoids prematurely forcing the existing legacy WebSocket behavior through an
  abstraction before parity is proven.
- add idle/provisional-session eviction and replay retention policies
- revisit per-logical-session active-prompt tracking in `AcpAgentSession`
- expose richer diagnostics / observability hooks
- decide whether compatible provisional session streams remain necessary after the RFD is clarified
