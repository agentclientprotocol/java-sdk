# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
[0.15.0]: https://github.com/agentclientprotocol/java-sdk/releases/tag/v0.15.0
