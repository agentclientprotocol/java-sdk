# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.18.0] - 2026-09-25

Remote agents: the Streamable HTTP and WebSocket transport from the ACP RFD, on plain `http://` and
`https://`, plus the fixes found reviewing it, and a choice of Jackson 2 or Jackson 3. Three changes
need attention when upgrading: `acp-core` no longer contains a JSON implementation, so a project
that depends on `acp-core` alone must add a JSON module; building a second client on an
already-connected transport now fails at construction; and `JacksonAcpJsonMapper` built from a bare
`new ObjectMapper()` is now strict about unknown fields.

### Added

- **Streamable HTTP transport** (RFD
  [streamable-http-websocket-transport](https://agentclientprotocol.com/rfds/streamable-http-websocket-transport)),
  by @kamikaz1k (#7). `StreamableHttpAcpClientTransport` in `acp-core` talks to a remote agent over
  HTTP POST with SSE response streams (one connection stream plus one stream per session, `Acp-Connection-Id`
  / `Acp-Session-Id` headers, `DELETE` to close); the new module **`acp-streamable-http-jetty`** provides
  `StreamableHttpAcpAgentTransport`, a Jetty listener that serves HTTP/SSE and a WebSocket upgrade on the
  same path and hosts one agent per remote connection through `AcpAgentFactory` and `RemoteAcpConnection`.
  Header names and path match the TypeScript and Rust SDKs. Each SSE stream is a mailbox: events sent
  while no subscriber is attached, or not yet written to one that went away, are delivered in order
  when the client reopens it. Closing a connection (`DELETE`) cancels its in-flight prompts.
- **Limits and keep-alive for the HTTP agent transport** (`StreamableHttpAcpAgentTransportOptions`): POST bodies
  are capped (16 MiB by default, 413 beyond), the WebSocket send queue and the number of provisional
  `session/load` streams per connection are bounded, a failed `session/load` leaves no provisional state,
  the SSE mailbox and backpressure limits are configurable, attached streams get a `: keep-alive`
  comment every 15 s so proxies do not cut idle connections, and a new GET on a stream takes it over
  from a subscriber the server may not yet know is dead instead of fanning out duplicates.
  One HTTP/2 connection may hold 1,024 concurrent streams (`maxConcurrentStreamsPerConnection`), not
  Jetty's default 128: each attached SSE stream holds one for its lifetime, and at the limit Jetty
  sends GOAWAY, which drops every exchange on the connection.
- **HTTP/2 over plain `http://`.** The RFD requires HTTP/2, and localhost without TLS is a first-class
  deployment. Over cleartext the JDK client only offers the h2c upgrade on a request without a body, so
  `initialize`, a POST, went out on HTTP/1.1. `StreamableHttpAcpClientTransport` now sends a bodiless
  GET first on `http://` endpoints, so against a server that speaks h2c (the SDK's own does) every
  request, streams included, runs on HTTP/2. Against one that does not, the probe settles on HTTP/1.1
  and later requests stop offering the upgrade, which some servers route to their WebSocket handler.
- **The Streamable HTTP client reconnects a dropped SSE stream.** When the server or the network closes
  the connection stream or a session stream, the client reopens it with a short backoff instead of
  failing the transport; the server's mailbox delivers whatever was not yet written. It gives up, as
  before, when the server answers 404 (the connection is gone) or after three reconnects in a row that
  delivered nothing.
- **Client sessions learn that their transport died.** `AcpClientTransport.awaitTermination()` (default:
  never) is implemented by the Streamable HTTP and WebSocket client transports; `AcpClientSession` fails
  pending requests at once with the cause, and every later request, instead of waiting out the request
  timeout.
- **`StreamableHttpAcpServlet` is public and mountable** in any Servlet 6 container (Spring Boot, Tomcat,
  Jetty, Undertow) at the application's own path, from a JSON mapper, an `AcpAgentFactory` and optional
  `StreamableHttpAcpAgentTransportOptions`; register it with async support. It owns its connections: the
  container's `init()` starts the SSE keep-alive and `destroy()` closes every connection, cancelling
  in-flight prompts. It serves the HTTP/SSE profile; the WebSocket upgrade on the same path needs
  `StreamableHttpAcpAgentTransport`, which now mounts this servlet on its own Jetty server.
- **JSON modules: `acp-json-jackson2` and `acp-json-jackson3` (#12).** The Jackson 2 mapper moved out
  of `acp-core` into `acp-json-jackson2`, and `acp-json-jackson3` adds `Jackson3AcpJsonMapper`
  (package `com.agentclientprotocol.sdk.json.jackson3`) on Jackson 3 (`tools.jackson`, 3.1.5, the
  version Spring Boot 4.1 manages). Both read the same schema records, whose Jackson annotations
  Jackson 3 reads unchanged, and write the same bytes: the schema serialization suite and an exact
  wire-format test run against each. The Jackson 3 default mapper is lenient and logs ignored
  properties at DEBUG like the Jackson 2 one, and restores Jackson 2 behaviour where Jackson 3
  changed a default that touches the wire (alphabetical property sorting, `null` for primitives,
  trailing content, enums via `toString()`); a strict `JsonMapper` passed to
  `new Jackson3AcpJsonMapper(...)` is honoured. Proposed by @bengbengbalabalabeng (#12).
- **Deterministic JSON mapper selection.** `AcpJsonMapperSupplier` has a `default int priority()`
  (0); `AcpJsonMapper.createDefault()` uses the highest, ties broken by class name, instead of
  whichever supplier came first on the classpath. Jackson 3 is -100 and Jackson 2 -200, so with both
  modules present Jackson 3 wins, and an application's own supplier wins over both. The system
  property `acp.json.mapper.supplier` names a supplier class explicitly.
- `CancelNotification` carries `_meta`.
- `AcpSyncClient(AcpAsyncClient)` is public: the supported way to have both APIs over one session.

### Changed

- **Breaking: `acp-core` no longer contains a JSON implementation (#12).** It depends on
  `jackson-annotations` only (for the schema records), not on `jackson-databind` or `jackson-core`.
  `acp-agent-support`, `acp-test`, `acp-websocket-jetty` and `acp-streamable-http-jetty` depend on
  `acp-json-jackson2`, so their users see no change. **If you depend on `acp-core` alone, add one
  JSON module**, otherwise `AcpJsonMapper.createDefault()` fails with "No AcpJsonMapperSupplier
  found on the classpath":

  ```xml
  <dependency>
      <groupId>com.agentclientprotocol</groupId>
      <artifactId>acp-json-jackson2</artifactId> <!-- or acp-json-jackson3 -->
      <version>0.18.0</version>
  </dependency>
  ```

  `JacksonAcpJsonMapper` and `JacksonAcpJsonMapperSupplier` keep their package
  (`com.agentclientprotocol.sdk.json`) and names, so code that constructs them compiles unchanged
  once the module is on the classpath. Proposed by @bengbengbalabalabeng (#12).
- **Single-turn enforcement is per logical session.** `AcpAgentSession` keyed its active-prompt lock
  per transport connection; over HTTP one connection carries many sessions, so it is now keyed by
  `sessionId` (`hasActivePrompt(sessionId)`, `getActivePromptSessionIds()`), matching the Kotlin SDK.
  Stdio and WebSocket agents, which see one session per connection, behave as before. (#7, #9)
- **Unknown-field policy moved from the schema to the mapper (#10).** Every schema record carried
  `@JsonIgnoreProperties(ignoreUnknown = true)`, which made the SDK tolerate fields it does not know
  (deliberate: the spec adds fields between releases and a newer agent must keep working) but also
  defeated any consumer's strict `ObjectMapper`, since a class-level annotation wins over
  `FAIL_ON_UNKNOWN_PROPERTIES`. The annotations are gone. The default mapper,
  `JacksonAcpJsonMapper.defaultObjectMapper()`, is lenient and logs each ignored property at DEBUG
  so spec drift is observable; a consumer who passes a strict mapper to `JacksonAcpJsonMapper` now
  gets strict behaviour. **If you construct `JacksonAcpJsonMapper` with a bare `new ObjectMapper()`,
  you now get Jackson's default, which fails on unknown fields**: start from
  `defaultObjectMapper()` instead. Unknown fields are not routed into `_meta`, which has its own
  spec-defined meaning. Reported by @KallivdH.
- **One shared timeout scheduler.** Every `AcpClientSession` and `AcpAgentSession` created its own
  scheduled thread pool for request timeouts; over the Streamable HTTP transport, which hosts one agent
  session per remote connection, that was one idle thread per connection. Timeouts now run on a single
  library-owned daemon timer (`AcpSchedulers.timeouts()`).
- The scheduler-hygiene test (`SchedulerBestPracticesTest`) now scans every module's production sources,
  not only `acp-core`.

### Deprecated

- **`WebSocketAcpAgentTransport` (`acp-websocket-jetty`) is deprecated for removal.** It serves a single
  WebSocket client. `StreamableHttpAcpAgentTransport` (`acp-streamable-http-jetty`) serves the WebSocket
  upgrade on the same path for any number of clients, one agent per connection, plus the Streamable HTTP
  profile; `WebSocketAcpClientTransport` clients connect to it unchanged.

### Fixed

- `WebSocketAcpAgentTransport` wrote each frame without waiting for the previous one to complete; Jetty
  allows one outstanding write per WebSocket session. Frames are now written one at a time, each after
  Jetty's completion callback, and a failed write is reported without ending the outbound stream.
- **A second client on an already-connected transport now fails at construction.** A transport
  instance carries exactly one session. `StdioAcpClientTransport.connect()` had no once-only guard
  (the WebSocket and agent transports did), so a second `AcpClient.async(transport)` or
  `AcpClient.sync(transport)` on the same instance subscribed the transport's unicast inbound sink a
  second time — logged and dropped as `Sinks.many().unicast() sinks only allow a single
  Subscriber` — and then started a second agent process, whose owner could never receive a
  response: its first request timed out after the full `requestTimeout`. That is the Spring Boot
  autoconfiguration failure reported in June 2026 (both an `AcpAsyncClient` and an `AcpSyncClient`
  bean built from one transport bean); it never depended on the Reactor or Boot version. Now the
  stdio transport refuses a second `connect()`, and `AcpClientSession` / `AcpAgentSession` surface a
  connect or start failure instead of dropping it: construction throws `IllegalStateException` when
  the transport refuses synchronously, and every later request fails immediately with the cause.
- **Second prompt on a session could be rejected or hang under CPU contention (#14).** The agent
  released its single-turn prompt lock in `doFinally`, *after* the response had already reached the
  client. On a starved machine (`taskset -c 0`, busy CI runners) the client's next prompt arrived
  before that release and was rejected with `-32000 There is already an active prompt execution`;
  the rejection's emission then collided with the still-running response emission on the same sink
  (`FAIL_NON_SERIALIZED`), which the shipped transports silently dropped and the in-memory test
  transport turned into a fatal error for the agent, so `AcpSyncClient.prompt` waited out the full
  `requestTimeout`. The lock is now released before the response is published, on the success and
  error paths alike, and a handler that throws before returning its `Mono` no longer holds the lock
  forever. Response emission in `StdioAcpAgentTransport`, `WebSocketAcpClientTransport`,
  `WebSocketAcpAgentTransport` and `InMemoryTransportPair` is serialised the way `sendMessage`
  already was, and a failed emission in the in-memory pair no longer terminates the agent. The
  reporter's repro is now a test, and CI runs the contention tests pinned to one CPU. Reported by
  @krickert.
- `WebSocketAcpAgentTransport` read its client session field twice around a null check while the
  socket's `onClose` could clear it, an occasional `NullPointerException` on close.
- The agent session now reads `sessionId` from typed `PromptRequest`/`CancelNotification` params as
  well as from maps, so in-process transports get the right lock owner in logs and cancel matching.

## [0.17.0] - 2026-08-28

Wire-format correction. No public API change: every constructor and accessor is unchanged, and the
ACP surface is identical to 0.16.1.

### Fixed

- **Duplicate discriminator on the wire.** Every polymorphic type declared its discriminator twice —
  once as Jackson's `@JsonTypeInfo` property on the interface and again as an explicit
  `@JsonProperty` record component — so both wrote it. `new TextContent("hello")` serialised to
  `{"type":"text","type":"text","text":"hello"}`, and every `session/prompt` the SDK sent carried a
  duplicate JSON key. Jackson-based peers accept duplicates and take the last, which is why this
  went unnoticed; a strict parser rejects the message outright. The discriminator components are now
  `Access.WRITE_ONLY` with `visible = true` on the type info, so the type id is written exactly once
  and is still populated on deserialization — previously it deserialised to `null`. 27 records
  across 6 hierarchies. No API change: every constructor and accessor is unchanged.

## [0.16.1] - 2026-08-24

Release-tooling patch. No protocol, dependency-closure or public API changes beyond a SLF4J patch
bump: the ACP surface is identical to 0.16.0.

### Build

- **Consumer-scoped SBOMs.** Every module artifact now publishes a CycloneDX 1.6 SBOM (classifier
  `cyclonedx`) rooted at that module and covering its compile and runtime scope only, so a
  consumer's dependency closure can be checked against what the artifact actually ships.
- The release workflow commits the release version bump before tagging, so each `vX.Y.Z` tag now
  points at a commit whose POMs carry that version.

### Changed

- SLF4J 2.0.17 → **2.0.18**.

## [0.16.0] - 2026-08-24

Maintenance dependency and release-tooling refresh. No protocol or public API changes: the ACP
surface remains identical to 0.15.0.

### Security

- Jackson 2.21.5 → **2.22.2**, Jetty 12.0.37 → **12.1.12**, and Reactor 3.6.12 → **3.8.7**.
- SLF4J, Logback, JUnit 5, Mockito, Maven build plugins, Central publishing, JaCoCo, and the optional
  OWASP dependency-check profile move to their current compatible stable releases.

### Changed

- README installation examples now point to 0.16.0 and retain 0.15.0 in the release history.

## [0.15.0] - 2026-08-21

Correctness and supply-chain hygiene. No protocol or public API changes: the ACP surface is
identical to 0.14.0, so this is a drop-in upgrade.

### Fixed

- **Notification ordering.** Incoming notifications were dispatched as independent
  fire-and-forget subscriptions, so a handler doing any async work could observe them out of
  order — visible with agents that stream many rapid `session/update` chunks. They are now
  serialized through a sink drained by `concatMap`, preserving arrival order. Reported and
  fixed by @ljiro (#13, closes #11).
- **Notifications lost on graceful close.** Following from the above, `closeGracefully()`
  completed the notification sink and disposed the drain subscription in the same synchronous
  block, discarding anything still queued. Because `AcpClient.SyncSpec` wraps every sync
  `sessionUpdateConsumer` with `subscribeOn(SYNC_HANDLER_SCHEDULER)`, sync clients always have
  async handlers, so a rapid burst could be lost entirely on close. `closeGracefully()` now
  waits for the drain to terminate before tearing the session down, bounded by the session's
  `requestTimeout` so a handler that never completes cannot hang shutdown. `close()` still
  interrupts immediately — the two methods now differ, as their names imply.
- Notifications arriving after shutdown has begun are still dropped — a graceful shutdown stops
  accepting new work while draining what is queued, and JSON-RPC notifications carry no delivery
  guarantee — but the log severity now distinguishes that expected case (DEBUG) from overflow,
  zero-subscriber and non-serialized emission, which lose traffic on a live session (ERROR).

### Security

- Jackson 2.21.2 → **2.21.5** and Jetty 12.0.14 → **12.0.37**, clearing 17 known advisories
  (5 high severity) reported against the published dependency closure. Both reach consumers as
  compile-scope transitives of `acp-core` and `acp-websocket-jetty`.

### Changed

- **`LICENSE` is now the verbatim Apache License 2.0.** The previous file was a paraphrase: it
  omitted section 6 (Trademarks) entirely, renumbered the sections that follow, rewrote the
  section 2 copyright grant, and narrowed the `Licensor` and `Work` definitions. Automated
  license detection classified the repository as `NOASSERTION` while every published POM
  declared Apache-2.0.
- `LICENSE` and a new `NOTICE` are now packaged under `META-INF` in every module artifact.
- Removed a redundant `<repositories>` declaration from the published parent POM; consumers no
  longer inherit a repository definition with snapshots enabled.

### Build

- Integration tests now execute. Three `*IT` classes existed, but Surefire's default includes do
  not match `*IT` and no Failsafe plugin was configured, so `mvn verify` silently skipped them.
- All GitHub Actions are pinned to commit SHAs.
- `HandlerExceptionTest` no longer races the async dispatch with a fixed sleep.

## [0.14.0] - 2026-06-11

Protocol currency: catching up to ACP spec v0.13.6 (June 2026). Supersedes the never-published
0.13.0 (its content ships here).

### Added

- `logout` method (`AcpAsyncClient`/`AcpSyncClient.logout`, `@Logout`, agent handler) — clears
  stored credentials.
- `session/delete` method (`deleteSession`, `@DeleteSession`, agent handler) — permanently deletes a
  stored session; gated on the `sessionCapabilities.delete` capability.
- `additionalDirectories` on `session/new`, `session/load`, `session/resume`, `session/fork` requests
  and on `SessionInfo` — extra workspace roots beyond `cwd`.
- Per-chunk `messageId` on `AgentMessageChunk`, `AgentThoughtChunk`, `UserMessageChunk`, plus
  `sendMessage(text, messageId)` / `sendThought(text, messageId)` convenience overloads on
  `PromptContext` and `SyncPromptContext`.
- **Provider configuration methods** (`providers/list`, `providers/set`, `providers/disable`),
  marked `@UnstableAcpApi`: client methods `listProviders`/`setProvider`/`disableProvider`, agent
  handlers + `@ListProviders`/`@SetProvider`/`@DisableProvider`, the `ProviderInfo` /
  `ProviderCurrentConfig` / `ProvidersCapabilities` types, and a `providers` capability on
  `AgentCapabilities` surfaced via `NegotiatedCapabilities.supportsProviders()`.
- `sessionCapabilities.delete` and `sessionCapabilities.additionalDirectories`, surfaced via
  `NegotiatedCapabilities` (`supports*`/`require*`).

### Changed

- Promoted the session config-option API to stable: removed `@UnstableAcpApi` from
  `SetSessionConfigOptionRequest`/`SetSessionConfigOptionResponse`, `SessionConfigOption`,
  `SessionConfigSelect`, `SessionConfigSelectOption`, `ConfigOptionUpdate`, and `@SetSessionConfigOption`
  — `session/set_config_option` and `session/set_mode` are now in the stable ACP schema. The
  `boolean` config-option variant (`SessionConfigBoolean`) remains an unstable SDK extension.
- Aligned Jackson to 2.21.2 (matches the agentworks-bom managed set / Spring Boot's jackson-bom).
- WebSocket transport maximum message size increased to 4 MB.

### Deprecated

- The session-model API — `session/set_model` (`setSessionModel`, `@SetSessionModel`, handler),
  `SetSessionModelRequest`/`SetSessionModelResponse`, `SessionModelState`, `ModelInfo`, and the
  `models` field on the new/load/resume/fork session responses — is deprecated for removal. The spec
  removed it (June 2026, v0.13.5); expose model selection through `session/set_config_option` with a
  config option whose `category` is `"model"` instead. Scheduled for removal in a future release.

### Fixed

- WebSocket client transport no longer echoes agent requests back to the agent.

## [0.9.0] - 2026-02-XX

### Added

#### Core SDK
- Pure Java implementation of Agent Client Protocol (ACP) specification
- `AcpSchema` — complete protocol type definitions (sealed interfaces and records)
- `AcpSyncClient` — synchronous blocking client
- `AcpAsyncClient` — reactive async client with Project Reactor
- `AcpClientSession` — low-level client session implementation
- `StdioAcpClientTransport` — stdio transport for launching agents as subprocesses
- `WebSocketAcpClientTransport` — JDK-native WebSocket client transport (no extra dependencies)
- `AgentParameters` — process configuration builder for agent launch

#### Agent SDK
- `AcpSyncAgent` — synchronous agent with blocking handlers
- `AcpAsyncAgent` — reactive agent with `Mono`-returning handlers
- `StdioAcpAgentTransport` — stdio transport for agents
- `SyncPromptContext` — convenience API for sending messages, reading files, requesting permissions
- All handler types: initialize, newSession, loadSession, prompt, setSessionMode, setSessionModel, cancel

#### Annotation-Based Agent API
- `@AcpAgent` — class-level agent annotation with name/version
- `@Initialize`, `@NewSession`, `@LoadSession`, `@Prompt`, `@Cancel` — handler annotations
- `@SetSessionMode`, `@SetSessionModel` — session configuration annotations
- `@SessionId`, `@SessionState` — parameter annotations
- `AcpAgentSupport` — bootstrap and builder for annotation-based agents
- Flexible method signatures with automatic parameter resolution
- Auto-conversion of return values (`String` → `PromptResponse`, `void` → `endTurn()`)
- Interceptor support for cross-cutting concerns
- Custom argument resolvers and return value handlers

#### Capabilities
- `NegotiatedCapabilities` — capability negotiation between client and agent
- Client capabilities: file read/write, terminal execution, permission requests
- Agent capabilities: load session, image content, slash commands
- `require*()` methods that throw `AcpCapabilityException` if unsupported

#### Error Handling
- `AcpProtocolException` — structured JSON-RPC errors with codes
- `AcpCapabilityException` — capability not supported
- `AcpConnectionException` — transport-level failures
- Standard error codes via `AcpErrorCodes`

#### Transports
- Stdio transport (client and agent)
- WebSocket client transport (JDK-native)
- WebSocket agent transport (Jetty-based, `acp-websocket-jetty` module)
- In-memory transport pair for testing (`acp-test` module)

#### Testing
- `InMemoryTransportPair` — bidirectional in-memory transport for unit tests
- `MockAcpClient` — mock client builder with file content fixtures
- Fast, deterministic testing without subprocess I/O

#### Protocol Compliance
- Full SessionUpdate types: AgentMessageChunk, AgentThoughtChunk, ToolCall, ToolCallUpdateNotification, Plan, AvailableCommandsUpdate, CurrentModeUpdate
- MCP server configuration in session requests
- `_meta` extensibility on all protocol messages
- All StopReason values: END_TURN, MAX_TOKENS, REFUSAL, CANCELLED

#### Infrastructure
- Maven Central Portal publishing configuration
- CI workflow with GitHub Actions
- 258 unit tests
- Integration tests with Gemini CLI

### Dependencies
- Java 17 (LTS)
- Project Reactor 2023.0.12
- Jackson 2.18.2
- MCP JSON utilities 0.15.0-SNAPSHOT
- SLF4J 2.0.16

[0.9.0]: https://github.com/agentclientprotocol/java-sdk/releases/tag/v0.9.0
[0.18.0]: https://github.com/agentclientprotocol/java-sdk/releases/tag/v0.18.0
[0.17.0]: https://github.com/agentclientprotocol/java-sdk/releases/tag/v0.17.0
[0.16.1]: https://github.com/agentclientprotocol/java-sdk/releases/tag/v0.16.1
[0.16.0]: https://github.com/agentclientprotocol/java-sdk/releases/tag/v0.16.0
[0.15.0]: https://github.com/agentclientprotocol/java-sdk/releases/tag/v0.15.0
