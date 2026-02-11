# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
