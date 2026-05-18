# Plan: Streamable HTTP Client Transport

> **Status**: Milestone one implemented  
> **Created**: 2026-05-17  
> **Primary goal**: Java clients can communicate with compliant remote ACP agents over the Streamable HTTP transport.

## Goal

Add a client-side Streamable HTTP transport to `acp-core` so applications can use the existing Java client API against compliant remote ACP agents without changing their own code.

This first milestone is intentionally client-only:

- implement `StreamableHttpAcpClientTransport`
- preserve the existing ACP client API surface
- prove the wire contract with an in-repo TypeScript conformance fixture
- defer remote transport negotiation, Java server support, and reconnect/resume behavior

## Milestone-One Result

Implemented in this branch:

- `StreamableHttpAcpClientTransport`
- preserved public ACP client API
- compatibility note + isolated forwarding path for the existing client handler-emission ambiguity
- in-repo TypeScript fixture with golden transcripts
- Java unit + integration coverage for:
  - initialize bootstrap
  - cookie persistence
  - connection SSE
  - `session/new`
  - prompt flow with session updates
  - `session/request_permission`
  - `session/load`
  - wrong-stream responses
  - strict-routing rejection
  - two logical sessions
  - fixture validation failures for missing connection headers, missing cookies, and invalid SSE `Accept`

## Contract Decisions

### Public API

- Add `StreamableHttpAcpClientTransport` in `acp-core`.
- Keep construction symmetrical with `WebSocketAcpClientTransport`:
  - `new StreamableHttpAcpClientTransport(endpointUri, jsonMapper)`
  - `new StreamableHttpAcpClientTransport(endpointUri, jsonMapper, httpClient)`
- Use a transport-owned default `CookieManager`, while allowing advanced callers to inject a custom `HttpClient`.
- Expose a public routing mode:
  - `COMPATIBLE`
  - `STRICT`

### Client / Transport Boundary

- `AcpClientSession` remains transport-agnostic and continues to own ACP request/response semantics.
- Streamable HTTP owns HTTP-only concerns internally:
  - `Acp-Connection-Id`
  - `Acp-Session-Id`
  - SSE stream lifecycle
  - routing correlation
  - cookie propagation
- Preserve current WebSocket-compatible client handler-emission forwarding for behavioral parity in the first implementation.
- Isolate that compatibility behavior behind a small helper and flag it in code as an unresolved contract ambiguity.

### Lifecycle

- `connect(...)`
  - registers the inbound handler
  - prepares resources
  - performs no network I/O by itself
- `initialize`
  - is the first real HTTP exchange
  - sends `POST /acp` without `Acp-Connection-Id`
  - captures `Acp-Connection-Id` and cookies from the `200 OK`
  - opens the connection-scoped SSE stream before delivering the initialize response upward
- `session/new`
  - sends the POST first
  - receives its JSON-RPC response on the connection stream
  - opens the returned session’s SSE stream
  - completes only after that session stream is established
- `session/load`
  - opens the session stream first
  - sends the POST second
  - receives its response on the connection stream
- Session-scoped outbound messages require an already-open session stream.
- No automatic reconnect / resume behavior in milestone one.
- `closeGracefully()`
  1. stop accepting new outbound work
  2. cancel local SSE readers
  3. send `DELETE /acp` with `Acp-Connection-Id`
  4. clear local routing and stream state

### Routing

- Use an explicit routing table for known ACP methods.
- Compatible-mode fallback for unknown outbound requests / notifications:
  - `params.sessionId` present → session-scoped
  - otherwise → connection-scoped
- Strict mode rejects unknown outbound request / notification methods that lack explicit routing.
- The transport owns a minimal routing ledger:
  - `outbound request id -> request kind + expected response scope`
  - `inbound request id -> scope required for the later outbound response`
  - `session id -> open session SSE stream`
- Wrong-stream responses are protocol errors.
- Unknown response ids retain current Java SDK parity and are left to the session layer’s existing behavior.

### SSE Model

- Treat each SSE `data:` payload as one JSON-RPC message.
- Ignore comments / keep-alives.
- Preserve order per SSE stream.
- Do not impose a synthetic global order across different streams.
- Treat SSE as the source of truth for server feedback and request completion, not as a receipt log for every POST envelope.

## Test Harness

Create an in-repo TypeScript fixture:

```text
test-fixtures/streamable-http-server/
```

The fixture will be:

- HTTP-only in the first milestone
- strict by default
- scenario-driven with named startup-selected scenarios
- runnable manually and from Java integration tests
- the single owner of canonical transcript serialization

Golden transcripts will live beside the fixture:

```text
test-fixtures/streamable-http-server/golden/
```

### Milestone-One Scenarios

- initialize bootstrap
- cookie persistence
- connection SSE stream
- `session/new`
- prompt flow with session updates
- agent → client `session/request_permission`
- `session/load`
- validation failures for wrong / missing headers
- missing cookie
- wrong-stream response
- strict-routing rejection
- light two-session coverage

### Future Harness Scenarios

- reconnect / resume behavior
- concurrency / stress coverage
- broader interop matrix
- WebSocket scenarios for a future composite remote transport

## Deferred Work

- Composite `RemoteAcpClientTransport`
  - prefer WebSocket
  - fall back to Streamable HTTP
- Java server-side Streamable HTTP transport
- reconnect / resume behavior once the protocol defines it
- richer debugging / observability hooks
- broader interoperability testing against an official compliant server when one exists
- deeper multi-session stress coverage

## Known Ambiguity / Follow-Up Decision

### Client handler-emission forwarding

The existing WebSocket client transport forwards any message emitted by the registered handler back onto the transport. `AcpClientSession` also sends responses explicitly through `sendMessage(...)`, so the client-side contract is currently ambiguous.

Decision for this milestone:

- preserve WebSocket-compatible forwarding in the new HTTP transport for parity
- isolate the forwarding path in a small helper
- document the ambiguity in code and in this plan
- have the default `AcpClientSession` consume inbound messages without re-emitting them,
  because it already sends legitimate outbound replies explicitly through `sendMessage(...)`

Follow-up:

- decide whether `AcpClientTransport` should become explicitly receive-only on the client side
- if so, remove the compatibility forwarding path from both transports in a focused cleanup

## Non-Goals for the First Milestone

- WebSocket fallback orchestration
- server-side Java transport
- automatic reconnect / resume
- transport-specific public debugging APIs
- global ordering across streams
