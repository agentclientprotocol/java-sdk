# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.80.0] - 2026-10-04

### Security

- **A handler's unexpected exception no longer sends its message to the peer.** A request handler
  that failed with an exception other than `AcpProtocolException` was answered `-32603` with the
  exception's message, which can carry paths, SQL, URLs with credentials or tokens. It is now
  answered `-32603` with the message "Internal error", on both sides, and the exception is logged
  at WARN, with its stack trace, by the side that handled the request. An `AcpProtocolException`
  is unchanged: its code, message and data are the handler's intended answer. Migration: a handler
  whose exception message the peer should see throws `AcpProtocolException` with that message.

- Jackson 2.22.2 → **2.22.3** and Jackson 3.1.5 → **3.1.7**, clearing CVE-2026-89407, CVE-2026-89425,
  CVE-2026-91776, CVE-2026-91777 (both lines) and CVE-2026-19032, CVE-2026-68497, CVE-2026-83557 (Jackson 3).
  0.18.0 shipped with the affected versions; applications can override the versions now.

### Added

- **Micronaut: `acp.client.capabilities.elicitation-form`, `elicitation-url` and
  `boolean-config-options`** (default `false`), as Spring Boot and Quarkus already offer. The
  Micronaut client configuration hard-coded the three as not advertised, so a Micronaut client
  could not advertise elicitation or boolean config options from its settings.

- **Transport constructors without a JSON mapper:** `StreamableHttpAcpClientTransport(URI)`,
  `WebSocketAcpClientTransport(URI)`, `StreamableHttpAcpAgentTransport(int port, AcpAgentFactory)`
  and `StreamableHttpAcpServlet(AcpAgentFactory)` use `AcpJsonMapper.createDefault()`, as the stdio
  transports already did.

- **`AgentParameters.Builder.inheritEnvironment(boolean)`: start a stdio agent without the
  client's environment.** The "safe" default variables of `AgentParameters` (HOME, PATH, USER and
  the like) had no effect: `StdioAcpClientTransport` added them to the whole environment the
  process inherits, so every secret in the client's environment reached the agent. Inheriting stays
  the default and is now documented as such. With `inheritEnvironment(false)` the process starts
  from an empty environment and gets only `getEnv()`: the safe defaults and the variables added on
  the builder (`addEnvVar("GEMINI_API_KEY", ...)` for a key the agent needs).
  `AgentParameters.isInheritEnvironment()` reports the choice.

- **Annotated handlers may return a `CompletionStage` or a single-value `Publisher`**, besides
  the value itself and a `Mono`, with the same meaning: the runtime waits for the value on the
  handler's thread. A `@Prompt` method's `Mono<String>` (or `CompletionStage<String>`) now sends
  the text as an agent message chunk and ends the turn, as a `String` does; it used to fail every
  prompt with `-32603`. **Breaking:** `MonoHandler` is renamed `AsyncValueHandler`, and
  `getMonoGenericType` is now `getValueType`.

- **`AcpAgentSupport.Builder.run()`** builds the agent on its transport, starts it and blocks until
  the transport ends: `AcpAgentSupport.create(new MyAgent()).transport(new StdioAcpAgentTransport()).run()`,
  the bootstrap the README and Javadoc showed, now compiles.

- **Typed values in `@SetSessionConfigOption` methods.** New parameter annotations `@ConfigId`
  (the option id, a `String`) and `@ConfigValue` (the new value, typed by the parameter: `String`
  for a select option, `boolean`/`Boolean` for a boolean option, `Object` for either), resolved by
  the new `ConfigOptionResolver`. A value of the other kind than the parameter's is answered
  `-32602` (invalid params) without calling the method. The SDK still does not reject an id or a
  value the session never offered; that needs per-session option tracking and is the method's to
  check.
- **Spring Boot autoconfiguration and starter, now part of the SDK.** The former
  `spring-ai-community/acp-autoconfig` project moves into this repository, with its history, as two modules
  released with the SDK:
  - `com.agentclientprotocol:acp-spring-boot-starter` and `com.agentclientprotocol:acp-spring-boot-autoconfigure`,
    for Spring Boot 4.1. Boot's BOM is imported by these two modules only; the SDK's other modules do not depend on Spring.
  - Packages `com.agentclientprotocol.sdk.spring.boot.autoconfigure` (`.client`, `.agent`).
  - **Client:** an `AcpSyncClient` and an `AcpAsyncClient` over one transport from `spring.acp.client.transport.*`:
    stdio (`stdio.command`), WebSocket (`websocket.uri`) or Streamable HTTP (`http.uri`). An explicit `type`
    without its property fails at startup, naming the property. `AcpClientCustomizer` beans register session-update,
    permission, file system and terminal handlers. File system and terminal capabilities are off by default:
    advertise one only together with its handler.
  - **Agent:** the application's single `@AcpAgent` bean is served over stdio (the default) or, with
    `spring.acp.agent.transport.type=http` and `acp-streamable-http-jetty` on the classpath, over Streamable
    HTTP. In a servlet web application that means on the application's own server; otherwise on the SDK's
    listener, which also takes WebSocket upgrades. A stdio agent closes the application context when its client
    closes stdin, so a `spring.main.keep-alive` application exits 0 (`spring.acp.agent.shutdown-on-transport-end`).

  **Migrating from `org.springaicommunity:acp-spring-boot-starter` 0.12.0** (the old repository is redirected and
  publishes no further releases):
  - Coordinates: `org.springaicommunity:acp-spring-boot-starter` becomes `com.agentclientprotocol:acp-spring-boot-starter`,
    with the SDK's version. Drop any separate ACP SDK version pin; the starter brings the matching SDK modules.
  - Imports: `com.agentclientprotocol.autoconfigure.*` becomes `com.agentclientprotocol.sdk.spring.boot.autoconfigure.*`.
    Most applications import nothing from these packages; they configure `spring.acp.*`, whose keys are unchanged
    except as listed here.
  - Behaviour changes since 0.12.0:
    - the `spring.acp.agent.transport.websocket.*` properties are removed; they never configured anything;
    - `spring.acp.client.capabilities.read-text-file` and `write-text-file` now default to `false`;
    - a client-only application no longer gets an agent transport bean;
    - session updates with no consumer of your own are logged at DEBUG instead of a WARN;
    - `spring.acp.agent.request-timeout` and `spring.acp.client.request-timeout` have no default of their own:
      unset, they keep the SDK's one default request timeout (60 s), so the property follows the SDK;
    - several client transports set with no `spring.acp.client.transport.type` fail at startup, naming them; the
      websocket, then http, then stdio one used to win silently. Set the type, or leave only one set;
    - the listener-only agent properties move: `spring.acp.agent.transport.http.port` becomes
      `spring.acp.agent.transport.http.listener.port`, and `...http.max-concurrent-streams-per-connection` becomes
      `...http.listener.max-concurrent-streams-per-connection`. In a servlet web application they meant nothing;
    - `spring.acp.agent.transport.type=websocket` is accepted and means the same as `http`: the endpoint takes
      WebSocket upgrades on its path;
    - new properties: `spring.acp.client.prompt-timeout`, `spring.acp.client.capabilities.elicitation-form`,
      `elicitation-url` and `boolean-config-options`, `spring.acp.agent.cancel-grace-period` and
      `spring.acp.agent.max-prompt-duration`;
    - `ArgumentResolver` and `ReturnValueHandler` beans are added to the agent, as `AcpInterceptor` beans were;
    - `AcpClientCustomizer` moves to `com.agentclientprotocol.sdk.integration`, and `TransportType` is replaced by
      `com.agentclientprotocol.sdk.integration.AcpTransportType`;
    - closing the client waits at most its request timeout plus 10 s, then closes it at once;
    - a second `@AcpAgent` bean fails with "Found 2 @AcpAgent beans [...]", naming
      `spring.acp.agent.enabled=false` as the way to serve none;
    - the agent's handler methods can run on a Spring executor: `spring.acp.agent.handler-executor` names an
      `Executor` bean (a plain `TaskExecutor` is adapted), or `none` for the SDK's own pool. Unset, they run on
      the context's `applicationTaskExecutor` when `spring.threads.virtual.enabled=true` (a virtual thread per
      handler), and on the SDK's pool otherwise: without virtual threads Boot's executor is a pool of 8 threads,
      which would cap the prompts served at once.
  - Everything else in this release's breaking changes applies too: Spring applications compile against the
    SDK's API.

- **`acp-integration`: the framework-neutral half of the framework integrations**, package
  `com.agentclientprotocol.sdk.integration`. The Spring Boot, Micronaut and Quarkus integrations are thin layers
  over it, so each rule below holds the same in all three. Depends on `acp-core` and `acp-agent-support`, with
  `acp-streamable-http-jetty` optional, and on no framework (ArchUnit). See `acp-integration/README.md`.
  - Settings: `AcpAgentSettings` and `AcpClientSettings`, records with builders, and `from(SettingsSource, prefix)`
    for kebab-case key/value configuration. An unset value keeps the SDK default. `AcpTransportType`.
  - Transports: `AcpClientTransports` (an explicit type wins; otherwise the one transport configured; several with
    no type fail, naming them), `AcpAgentTransports.stdio()`, and `AcpListeners` for the SDK's listener and
    servlet, with `isListenerAvailable()`.
  - Agents: `AcpAgentDiscovery.requireSingle` over `AgentCandidate`s (user class plus instance supplier, so a
    proxied bean keeps its advice) or over class names for build-time discovery; `AcpAgents.builder`.
  - Clients: `AcpClients` (one async client and its sync facade, a DEBUG session-update consumer, the
    customizers in order) and `AcpClientCustomizer`, now one type for every framework.
  - Lifecycles: the `AcpHost` contract (`start`, `stopGracefully`, `stop(Duration)`, `termination`, `port`,
    `holdJvmUntilTermination`) with `AcpAgentHost` (one transport; a latched `onTransportEnd` action on a host
    thread, never run when the host stopped the agent) and `AcpListenerHost`; `AcpServletHost` and
    `AcpClientHost`.
  - Handler executors: `AcpAgents.builder(..., handlerExecutor)` passes a framework's executor to
    `AcpAgentSupport.Builder.handlerExecutor`, so annotated handlers run on the framework's threads instead of a
    second pool; unset keeps the SDK's own pool.

- **`acp-micronaut`: Micronaut 4 integration** (Java 17+), with `acp-micronaut-sample` (not published).
  - **Agent.** Annotate an `@AcpAgent` class `@Singleton` and it is served with the application
    context. The bean is found from its compile-time bean definition; exactly one is allowed.
    `AcpInterceptor`, `ArgumentResolver` and `ReturnValueHandler` beans are added to it, and handlers
    may also return a Reactive Streams `Publisher`. Configured under `acp.agent.*`: stdio by default
    (the context closes and the process exits 0 when the client closes the agent's input; SIGTERM
    closes the agent gracefully), or `http`/`websocket` through the SDK's Jetty listener on its own
    port, with every `StreamableHttpAcpAgentTransportOptions` limit as a property, plus
    `cancel-grace-period` and `max-prompt-duration`.
  - **Client.** `acp.client.*` builds the stdio, WebSocket or Streamable HTTP transport, an
    `AcpAsyncClient` customized by `AcpClientCustomizer` beans in order, and an `AcpSyncClient` over
    it; it closes gracefully, once, with the context.
  - The Micronaut platform BOM (4.10) is imported only by these modules, after the SDK's Reactor,
    Jackson, Jetty and JUnit BOMs, so they run on the SDK's versions. The wire format stays the SDK's
    Jackson mapper.
  - Built on `acp-integration`: `AcpClientCustomizer` and the transport type are its
    `com.agentclientprotocol.sdk.integration` types, and an explicit client type without its property
    fails with "...transport.type=http requires ...transport.http.uri".
  - On JDK 21 and later the agent's handler methods run on Micronaut's virtual-thread executor
    (`TaskExecutors.VIRTUAL`) instead of the SDK's own pool; on JDK 17 they stay on the SDK's pool.

- **Quarkus extension: `acp-quarkus` (with `acp-quarkus-deployment`).** One `@AcpAgent` class
  becomes a singleton bean (found at build time; a second one fails the build) and is served over
  stdio, or over Streamable HTTP and WebSocket on the Quarkus HTTP server
  (`quarkus.acp.agent.transport.type=http`): the SDK's servlet on `quarkus-undertow` plus a Vert.x
  WebSocket route on the same path, no second server. Stdio builds keep standard output for the
  protocol (console log on stderr, no banner, no HTTP listener) and exit when input ends. Handlers
  may return Mutiny `Uni`, and `@Prompt` may stream a `Multi`. `AcpSyncClient`/`AcpAsyncClient`
  beans come from `quarkus.acp.client.*` with `AcpClientCustomizer` beans. Configuration mirrors the
  Spring Boot starter's (`quarkus.acp.*`; the HTTP port is `quarkus.http.port`). JVM mode only.
  Built on Quarkus 3.40.1, whose BOM is imported only by these modules. See `acp-quarkus/README.md`.
  Cross-SDK: `quarkus-typescript-http` and `quarkus-typescript-ws` run the TypeScript client
  against a Quarkus-hosted agent. Built on `acp-integration`: `AcpClientCustomizer` and the transport
  type are its `com.agentclientprotocol.sdk.integration` types, `quarkus.acp.agent.transport.type`
  also accepts `websocket` (the same as `http`), and several client transports set with no
  `quarkus.acp.client.transport.type` fail at startup, naming them, as in the other frameworks.
  The agent's handler methods run on the `ManagedExecutor` (Quarkus' worker pool, with context
  propagation) instead of the SDK's own pool.
- **Prompt handlers see their prompt's cancellation.** `SyncPromptContext.isCancelled()` and
  `onCancel(Runnable)`, and `PromptContext.isCancelled()` and `whenCancelled()` (a `Mono<Void>`
  that completes on cancel), signal a cancel by `session/cancel` for the prompt's session or by
  `$/cancel_request` for its request, and also when the agent cancels the handler itself (the
  cancel grace period or `maxPromptDuration` passed, or the connection closed). An annotated
  `@Prompt` method reads it from the context it takes, with no `@Cancel` handler or shared state;
  a builder prompt handler likewise. After `session/cancel` the handler still answers `cancelled`
  within the cancel grace period; after `$/cancel_request` the agent has already answered, so the
  handler just stops. New `PromptResponse.cancelled()`.
  **Breaking** for code implementing `PromptContext` or `SyncPromptContext` itself (test doubles):
  implement the new methods.

- **`$/cancel_request` (ACP v1, Cancellation), both directions.** Client and agent sessions alike:
  - **Cancelling a request you sent:** dispose (cancel) the subscription to its `Mono` before the
    response arrives, directly or through `timeout(...)`, `take...`, or the SDK's own request
    timeout, and the session sends `$/cancel_request {"requestId": ...}` to the peer, once, after
    the request itself was written (never for a request that failed to send, was already answered,
    or was dismissed by closing the session). The caller's `Mono` is cancelled at once (Reactor
    semantics); the peer's late answer is expected and discarded at DEBUG. An agent prompt handler
    whose subscription is cancelled cancels the `fs/*`, `terminal/*` and
    `session/request_permission` requests it is waiting on the same way. Interrupting a thread
    blocked in a sync client or agent call cancels its request too.
  - **Graceful cancel:** `request.contextWrite(RequestCancellation.cancelWhen(trigger))` sends
    `$/cancel_request` when `trigger` emits or completes but keeps waiting, so the request ends with
    the peer's answer: its result, or an `AcpError` with code `-32800`. Works for every typed request
    method on both sides; at most one `$/cancel_request` per request either way.
  - **Answering a cancelled request:** on `$/cancel_request` the session cancels the handler's
    subscription and answers the request with error `-32800` (`Request cancelled`), unless the
    handler answered first; exactly one response either way (model checked with Lincheck,
    `InboundRequestsLincheckTest`). An unknown or already answered id is ignored. A cancelled
    `session/prompt` ends its turn before the answer is published; one already cancelled with
    `session/cancel` is answered with stop reason `cancelled` instead, as ACP v1 requires of a
    cancelled prompt. `$/cancel_request` is handled by the session and never reaches notification
    handlers; on the client it is not queued behind slow `session/update` consumers.
  - **Internal cancellation:** a handler that fails with `CancellationException`, an interrupt, or
    an `AcpProtocolException` with code `-32800` is answered `-32800` (a prompt being cancelled:
    stop reason `cancelled`). Closing a session cancels the requests it is still handling and
    answers them `-32800` where the transport still delivers.
  - Over Streamable HTTP, `$/cancel_request` is connection-scoped both ways, like every `$/`
    method and as in the Rust SDK: the client posts it without `Acp-Session-Id`, the agent sends
    it on the connection stream. It can therefore overtake a request still on a session stream;
    the receiver then ignores it as an unknown id (the spec's MAY) and the request completes.
  - Params of a received `$/cancel_request` without a string or integer `requestId` are ignored
    (a notification has no response to carry `-32602`).
  - `AcpSchema.METHOD_CANCEL_REQUEST`, `AcpSchema.CancelRequestNotification`,
    `RequestCancellation`, `AcpSchedulers.timeoutDelivery()`.
  - Cross-SDK suite: the Java programs run `cancel-request.client`, `cancel-request.agent` and
    `cancel-request.unknown`; their `P2` expected failures are gone.

- **Prompt timeouts on the agent builders** (`AcpAgent.async(...)`, `AcpAgent.sync(...)` and
  `AcpAgentSupport.builder()`), also as `PromptTimeouts` on the `AcpAgentSession` constructor:
  - `cancelGracePeriod(Duration)`: how long a prompt handler has to answer after `session/cancel`
    before the agent answers `cancelled` itself. Default 60 seconds (see Changed).
  - `maxPromptDuration(Duration)`: how long a prompt may run at all. When it passes, the agent
    cancels the handler and answers with JSON-RPC error `-32800` (request cancelled), message
    `Prompt exceeded maxPromptDuration of <duration>` and data `{"maxPromptDuration": "<ISO-8601>"}`
    (ACP v1, Cancellation: an internally cancelled request, an internal timeout included, answers
    `-32800`); a prompt already being cancelled is answered `cancelled` instead. Default: none, as
    a prompt turn can legitimately run for a long time.

  `Duration.ZERO` turns either off; a negative or null duration is rejected. Either way the forced
  answer leaves through the normal path: the turn ends just before it is published, it follows
  every update the handler had already sent, and exactly one answer is sent when the handler
  answers just as a timer fires (model checked with Lincheck, `PromptAnswerLincheckTest`). The
  timers run on the SDK's shared timeout timer, with no new threads. Cancelling a sync handler
  interrupts its thread (the SDK's sync handler scheduler interrupts on cancel); a handler that
  ignores the interrupt, or any handler that keeps running after its subscription is cancelled,
  has the updates it then sends through its prompt context dropped (see Fixed). `AcpErrorCodes.REQUEST_CANCELLED` (`-32800`) is new.
- **`session_info_update`** (`AcpSchema.SessionInfoUpdate`): the agent tells the client the
  session's title and last activity time. Stable in ACP v1. Known limit: the schema lets a peer
  send `null` to clear a field; the record reads an explicit `null` like a missing field and never
  writes one, so it cannot express a clear.
- **`configOptions` on `NewSessionResponse`, `LoadSessionResponse` and `ResumeSessionResponse`**
  (ACP v1 schema; model selection moved to config options when `session/set_model` was removed).
  An agent advertises its config options and their current values when the session is created,
  loaded or resumed, and the client reads them from the response. New constructors
  `NewSessionResponse(sessionId, modes, configOptions)` and `LoadSessionResponse(modes,
  configOptions)` / `ResumeSessionResponse(modes, configOptions)`.
- **Tool call `name`** on `ToolCall`, `ToolCallUpdateNotification` and `ToolCallUpdate` (stable in
  ACP v1 since 2026-09-17): the programmatic name of the tool being invoked, next to the
  human-readable `title`. Optional; absent and `null` both mean no name.
- **Terminal authentication** (stable in ACP v1 since 2026-08-20): the `terminal` auth method
  (`AcpSchema.AuthMethodTerminal`, with the extra `args` and `env` the client runs the agent
  program with for an interactive login) and the client capability `auth.terminal`
  (`AcpSchema.AuthCapabilities` on `ClientCapabilities.auth`). Agents check it with
  `NegotiatedCapabilities.supportsTerminalAuth()` / `requireTerminalAuth()`.
- **Boolean config options are stable** (ACP v1 since 2026-07-06): `SessionConfigBoolean` is no
  longer `@UnstableAcpApi`. The client capability `session.configOptions.boolean`
  (`ClientCapabilities.session`, `ClientSessionCapabilities`, `SessionConfigOptionsCapabilities`,
  `BooleanConfigOptionCapabilities`; `SessionConfigOptionsCapabilities.withBoolean()`) tells the
  agent it may send them; agents check `NegotiatedCapabilities.supportsBooleanConfigOptions()`.
- **Building a model picker and other categorized config options.** Model choice is a select
  config option with `category: "model"`, and before only the 8-argument canonical
  `SessionConfigSelect` constructor took a category. New:
  - `SessionConfigSelect.model(id, name, currentValue, options)`, with `options` a
    `List<SessionConfigSelectOption>` or a (grouped) `SessionConfigSelectOptions`: a select
    option with category `"model"`.
  - `SessionConfigSelect.builder()`: `id`, `name`, `description`, `category`, `currentValue`,
    `options(List)` (flat), `groups(List<SessionConfigSelectGroup>)`, `options(SessionConfigSelectOptions)`
    and `meta`, then `build()`.
  - `SessionConfigBoolean.builder()`: `id`, `name`, `description`, `category`,
    `currentValue(boolean)` and `meta`, then `build()`.
  - `AcpSchema.SessionConfigOptionCategory`: the categories ACP v1 reserves, as `String`
    constants `MODE`, `MODEL`, `MODEL_CONFIG` and `THOUGHT_LEVEL`. `category` stays an open
    `String`; custom categories start with `_`.

  `build()` throws `IllegalStateException` naming the first missing required field (`id`, `name`,
  `currentValue`, and for a select the options).
- **`ClientCapabilities.builder()` and `AgentCapabilities.builder()`** reach every field without
  positional `null`s. Advertising boolean config options was the 6-argument canonical
  constructor with three `null`s; it is now
  `ClientCapabilities.builder().session(ClientSessionCapabilities.withBooleanConfigOptions()).build()`.
  - `ClientCapabilities.Builder`: `fs`, `terminal`, `session`, `auth`, `elicitation`, `meta`.
    It starts from `new ClientCapabilities()` (file system `false`/`false`, terminal `false`).
  - `AgentCapabilities.Builder`: `loadSession`, `sessionCapabilities`, `mcpCapabilities`,
    `promptCapabilities`, `auth`, `providers` (`@UnstableAcpApi`), `meta`. It starts from
    `new AgentCapabilities()` (no `session/load`, default MCP and prompt capabilities).
  - `ClientSessionCapabilities.withBooleanConfigOptions()`: `{"configOptions": {"boolean": {}}}`.
- **Agent capability `auth.logout`** (`AgentCapabilities.auth`, `AgentAuthCapabilities`,
  `LogoutCapabilities`; `AgentAuthCapabilities.withLogout()`): the agent advertises that it
  supports `logout`, and clients check `NegotiatedCapabilities.supportsLogout()` /
  `requireLogout()` before calling it.
- **`_meta` on every record the ACP schema gives it.** 48 records lacked it, among them
  `RequestPermissionRequest`/`Response`, the `fs/*` and `terminal/*` requests and responses,
  `AuthenticateRequest`/`Response`, `SetSessionModeRequest`/`Response`, `Implementation`, the
  MCP server records, `PlanEntry`, `AvailableCommand`, `ToolCallUpdate`, the tool call content
  records, `Annotations` and the capability records. Each gains a nullable `meta` component
  (`"_meta"` on the wire) as its last component, and keeps a constructor with its previous
  components, so existing calls compile; the exception is `ToolCallUpdate` (nine components
  before `_meta`), which instead gains `ToolCallUpdate(toolCallId, title, kind, status)` for the
  common permission-request case (migration: use it, or add `null` for `_meta`). A test walks a copy of the v1.9.1 schema
  (`acp-core/src/test/resources/schema/v1/schema.json`) and fails on any object with `_meta`
  whose record lacks it; the elicitation records are covered too (below). Not covered yet: the
  presence markers typed `Object` (they keep `_meta` as a map entry). `CancelRequestNotification`
  carries `_meta` (with `$/cancel_request`, below).

- **`elicitation/complete` reaches a typed client handler:** `AcpClient.async(...)
  .completeElicitationHandler(Function<CompleteElicitationNotification, Mono<Void>>)` and
  `AcpClient.sync(...).completeElicitationHandler(Consumer<CompleteElicitationNotification>)`. Before,
  the agent could send the notification but a client could only read it as raw params through
  `notificationHandler`. The spec requires clients to ignore unknown or already-completed
  elicitation IDs; the handler sees every notification and does that check.
- `AcpSyncAgent.createElicitation` and `AcpSyncAgent.completeElicitation`, matching the async agent.
- `CreateElicitationResponse.accept()` without content (for an accepted URL elicitation), and the
  mode constants `CreateElicitationRequest.MODE_FORM` and `MODE_URL`.
- The elicitation records carry every field of the stable schema: `_meta` on `ElicitationSchema`,
  the five property schemas, both multi-select item types and `EnumOption`, and `description` on
  `EnumOption`. The constructors without them remain, except for the two that would take seven or
  more arguments.
- **Custom extension methods (`_`-prefixed), both directions, on all four APIs.** ACP v1
  (Extensibility) reserves method names that start with `_` for custom requests and notifications.
  - Agents can now serve them: `AcpAgent.async(..)` and `AcpAgent.sync(..)` builders take
    `extRequestHandler(method, paramsType, handler)` and
    `extNotificationHandler(method, paramsType, handler)`, with a `TypeRef` for typed params, or
    without one for the raw JSON value (`Map`, `List`, `String`, `Number`, `Boolean`).
    Annotation-driven agents use `@ExtRequest("_name")` and `@ExtNotification("_name")`
    (`acp-annotations`); the method's one parameter, if any, receives the params read as its type.
  - Both sides can now send them: `AcpAsyncAgent`, `AcpSyncAgent`, `AcpAsyncClient` and
    `AcpSyncClient` have `sendExtRequest(method, params, resultType)`, `sendExtRequest(method, params)`
    (raw result) and `sendExtNotification(method, params)`. A `"result": null` answer completes the
    async send empty and makes the sync send return `null`.
  - Every one of these methods rejects a name that does not start with `_` with
    `IllegalArgumentException`, so a custom handler cannot replace a protocol method and a custom
    send cannot impersonate one. `ExtensionMethods` (`spec`) holds the rule. The client's untyped
    `requestHandler(method, ..)` and `notificationHandler(method, ..)`, which take any name, are
    unchanged.
  - Unhandled ones follow the spec, on both sides: a request is answered with `-32601` (Method not
    found), a notification is ignored. A handler that produces no result answers with `-32603`; return
    an empty map when there is nothing to return.
- `TypeRef.of(Type)`: a type reference for a type known only at runtime.
- **`StreamableHttpAcpAgentTransport` listens on an ephemeral port when given port 0.** Before, the
  constructor rejected 0 (`Port must be positive`), so a test or an embedding application had to
  reserve a port and race to rebind it. A port outside 0–65535 is rejected. `getPort()` returns
  the configured port (0 for an ephemeral one) until `start()` completes, then the port actually
  bound, which it keeps reporting after the listener closes (before, Jetty's negative "not open"
  value).
- **`AcpAgentSupport.Builder.buildFactory()`**: an `AcpAgentFactory` for a listener transport
  (`StreamableHttpAcpAgentTransport`, `StreamableHttpAcpServlet`) that creates a fresh agent per
  connection from one annotated handler bean. The bean is shared, like a Spring controller: every
  connection's agent invokes the same instance, concurrently, so its handlers (and interceptors,
  custom resolvers and return value handlers) must be thread-safe. Before, the documented pattern
  rebuilt the builder, and rediscovered the handler methods, per connection.
- **`@Authenticate`**: the annotation for an `authenticate` handler, which an annotated agent could
  not serve before (the client got `-32601`, Method not found). The method takes an optional
  `AuthenticateRequest` (`AuthenticateRequestResolver`) and returns an `AuthenticateResponse` or a
  `Mono` of one; throw `AcpProtocolException` with `AcpErrorCodes.AUTHENTICATION_REQUIRED` to reject
  the attempt.
- **Any annotated handler can take its connection's agent**: an `AcpSyncAgent` or `AcpAsyncAgent`
  parameter (`AgentResolver`, `AcpInvocationContext.getAgent()`), the agent serving the connection
  the request arrived on, so a handler can send session updates (a `ConfigOptionUpdate`, say) and
  requests to the client outside a prompt turn. Under `buildFactory()` one handler bean serves
  every connection and so cannot keep "its" agent in a field; each call now gets its own
  connection's. Extension handlers (`@ExtRequest`, `@ExtNotification`) may take these and
  `NegotiatedCapabilities` besides their one params parameter
  (`ExtensionParamsResolver.isConnectionType`).

- **Shortcuts for the messages real code writes most:** `new NewSessionRequest(cwd)` (no MCP
  servers), `new NewSessionResponse(sessionId)` (no modes or config options) and
  `PromptRequest.text(sessionId, text)` (a prompt of one text block). Each equals, and writes the
  same JSON as, the long form.

- **`defaultSessionUpdateConsumer(..)` on `AcpClient.AsyncSpec` and `SyncSpec`:** a session
  update consumer used only while no `sessionUpdateConsumer` is added, so a framework can set a
  default (such as logging each update at DEBUG) that the application's own consumer replaces,
  whichever is registered first. The Spring Boot autoconfiguration and the Micronaut and Quarkus
  client beans now set their DEBUG-logging consumer this way; it used to run beside every consumer a customizer
  added. `sessionUpdateConsumer` stays additive.

### Changed

- **`SessionCapabilities`' six- and four-argument constructors are `@UnstableAcpApi`.** Both take
  the unstable `fork` component (the four-argument one exists only to set it) but were not marked,
  so stable code could reach an unstable capability without a marker. `UnstableApiMarkerTest`
  now also checks constructors that set an unstable component. Migration: none; code that must
  avoid unstable API sets `additionalDirectories` through the canonical constructor or a builder
  answer.

- **Breaking: the client sends `additionalDirectories` only to an agent that advertises them, and
  an annotated agent can advertise them.** ACP lets a client send additional workspace
  directories only when the agent advertises `sessionCapabilities.additionalDirectories`, but
  `newSession`, `loadSession`, `resumeSession` and `forkSession` sent them to any agent. A call
  whose request names a non-empty `additionalDirectories` now fails with
  `AcpCapabilityException` (capability `sessionCapabilities.additionalDirectories`) without being
  sent, as other capability-gated calls do; no list or an empty one needs nothing. On the agent
  side, `@AcpAgent(additionalDirectories = true)` advertises the capability; it needs a
  `@NewSession` method, since the default `session/new` answer would drop the directories, which
  ACP forbids. A builder agent advertises it from its `initializeHandler`. Migration: a client
  check `getAgentCapabilities().supportsAdditionalDirectories()` before naming directories; an
  annotated agent that uses them add `additionalDirectories = true` to `@AcpAgent` (an
  `@Initialize` method that advertised them keeps working).

- **`AcpAgentSupport.Builder.cancelGracePeriod(..)` and `maxPromptDuration(..)` reject a negative
  or null value where it is set.** They stored any value: `build()` failed later, and
  `buildFactory()` built a factory whose every connection then failed to build its agent. Both
  now throw `IllegalArgumentException` at once. Migration: none for valid values.

- **Breaking: `AcpInterceptor.afterCompletion` receives the exception the call failed with.** It
  took only the context, and the runtime dropped the exception it had, so cleanup that must know
  how a call ended (a span status, a failure metric) had to stash the exception from `onError` in
  an attribute. The method is now `afterCompletion(AcpInvocationContext context, Throwable ex)`:
  `ex` is what the call failed with (what a step threw, or what `onError` threw in its place), and
  `null` when it produced a result, when `onError` returned a replacement, and when a `preInvoke`
  returned `false`. Migration: add the `Throwable` parameter to an `afterCompletion` override. An
  override without `@Override` keeps compiling but is no longer called, so add `@Override`.

- **The default `session/new` answer passes through the interceptors.** An annotated agent without
  a `@NewSession` method answers `session/new` with a random session id; that answer skipped the
  interceptors, while the `initialize` answer derived from the annotations went through them, so a
  logging or access-check interceptor missed session creation. It now passes through the chain
  like any handler method: `preInvoke` can veto it or reject it with an `AcpProtocolException`.
  Migration: an interceptor that should not act on `session/new` checks
  `context.getAcpMethod()`.

- **Breaking: an `@ExtNotification` method must return `void`.** A notification gets no answer,
  and a value the method returned was dropped without a word, while a `void` `@ExtRequest` was
  already rejected. Building the agent now rejects a non-`void` `@ExtNotification` method with an
  `IllegalStateException` naming the method. Migration: return `void`, or make it an
  `@ExtRequest` if the client expects an answer.

- **An extension notification without a handler is logged at DEBUG, not WARN.** ACP asks
  implementations to ignore notifications they do not recognize, and a peer can send many
  (`_auth/status_update` from some agents), each of which logged a warning on both sides. A
  `_`-prefixed notification without a handler is now logged at DEBUG; a protocol notification
  without a handler still logs a warning. Migration: none; raise the session logger to DEBUG to
  see them.

- **Breaking: `AcpError` extends `AcpException`.** It extended `RuntimeException` directly, so
  `catch (AcpException e)`, which the SDK documented as the one place to handle its failures,
  missed every error the peer answered with. `AcpException` is now the base of every failure of a
  call: `AcpError`, `AcpProtocolException`, `AcpCapabilityException`, `AcpConnectionException`
  and `AcpTimeoutException`. `AcpError` keeps its package, constructor, methods and messages.
  Migration: a multi-catch `catch (AcpException | AcpError e)` no longer compiles; catch
  `AcpException` alone. A `catch (AcpException e)` placed before a `catch (AcpError e)` now takes
  the peer's errors too; put the `AcpError` clause first.

- **Breaking: a lost connection fails a request with `AcpConnectionException`, on both sides.** A
  request still waiting when the transport ended failed with a plain `RuntimeException` ("ACP
  session with agent terminated"), and a request or notification sent after that with an
  `IllegalStateException` ("ACP client transport is not connected: ..." or "ACP agent transport is
  not started: ..."), so `catch (AcpConnectionException e)` missed the commonest connection failure,
  a peer that went away. Both are now `AcpConnectionException`, with the same messages and the
  transport's failure as the cause; requests waiting when a session is closed fail the same way.
  Building a client or agent on a transport that refuses at once (one already in use) still throws
  `IllegalStateException`. Migration: replace `catch (IllegalStateException e)` or
  `catch (RuntimeException e)` around calls that meant "the connection is gone" with
  `catch (AcpConnectionException e)`.

- **Breaking: a peer's error that escapes a handler is passed on unchanged.** A handler that called
  the other side and let the resulting `AcpError` escape answered its own request `-32603`, losing
  the peer's code (a `-32601` or `-32002` became an internal error). The `AcpError`'s JSON-RPC
  error, code, message and data, is now the answer, so a handler with nothing to add can let the
  failure propagate; `getError().toException()` is no longer needed for that. Migration: a handler
  that relied on such a failure being reported as `-32603` catches the `AcpError` and throws
  `AcpProtocolException` with the code it wants.

- **Breaking: an interceptor's `onError` can answer with an error of its own, and reaches only the
  interceptors that saw the call.** What `onError` threw used to be logged and ignored, so the
  client got the original failure, and the migration note for the removed `@AcpExceptionHandler`
  (handle the exception in `onError` and throw `AcpProtocolException`) did not work. Now an
  `AcpProtocolException` thrown from `onError` is the answer, with its code, message and data;
  any other exception thrown there is a fault in the interceptor and is answered `-32603`. Either
  ends the walk, and the original failure is attached as suppressed. `onError` was also called on
  every interceptor, including those whose `preInvoke` never ran or threw; it now reaches exactly
  the interceptors that get `afterCompletion`, those whose `preInvoke` returned `true`.
  Migration: an `onError` that throws expecting to be ignored should log and return `null`
  instead; one that relied on seeing failures from a `preInvoke` that did not pass should move
  that handling into its `preInvoke`.

- **A supported Jackson floor, checked at startup: Jackson 2.18.1 or later for
  `acp-json-jackson2`, Jackson 3.0.0 or later for `acp-json-jackson3`.** Frameworks manage their
  own Jackson version, and the SDK stated none: an older Jackson (Vert.x 4.5's jackson-core 2.16.1
  under jackson-databind 2.22.3, for example) failed much later with a `NoSuchMethodError` while
  reading a message. Each mapper supplier (`JacksonAcpJsonMapperSupplier`,
  `Jackson3AcpJsonMapperSupplier`) and mapper constructor now checks the jackson-core and
  jackson-databind on the classpath and fails with an `IllegalStateException` naming the version
  found and the version required. The floors are the oldest releases the SDK's tests pass with
  (2.18.0 drops unknown values of the open enumerations, and 2.17 the unknown fields the SDK keeps
  for forward compatibility). Quarkus 3.40 (Jackson 2.21.7) and Spring Boot 4.1 (Jackson 3.1.7) are
  within them. Migration: none for a
  consistent Jackson at or above the floor; otherwise align the Jackson artifacts on one version,
  for example by importing the `jackson-bom`.

- **Breaking: `StdioAcpClientTransport.awaitForExit()` is renamed `awaitProcessExit()`**, so it does
  not sit beside `awaitTermination()` (which completes when the transport ends, not the process).
  Interrupted, it now throws `CancellationException` and keeps the thread's interrupt flag; it used
  to throw a plain `RuntimeException` and clear the flag. Migration: rename the call; catch
  `CancellationException` instead of `RuntimeException` around it.

- **Breaking: sync lifecycle names line up.** `AcpSyncAgent.await()` is renamed
  `awaitTermination()`, matching `AcpAsyncAgent` and the transports. `AcpSyncAgent` and
  `AcpAgentSupport` implement `AutoCloseable`, and their `close()` now means what it means on
  `AcpSyncClient`: close gracefully, waiting at most 10 seconds, then close at once if that failed
  or took longer (`AcpSyncAgent.close()` used to close at once; `AcpAgentSupport.close()` used to
  wait up to the 5-minute block timeout and never force). New `AcpSyncClient.async()` returns the
  `AcpAsyncClient` it blocks on. Migration: replace `agent.await()` with
  `agent.awaitTermination()`; for the old immediate close, call `agent.async().close()`; use
  try-with-resources on the sync agent and the annotated agent.

- **Breaking: `PromptContext.sendUpdate(update)` and `SyncPromptContext.sendUpdate(update)` replace
  the two-argument `sendUpdate(sessionId, update)`.** The context belongs to one prompt's session,
  so the session ID was redundant (and a wrong one sent the update to another session). Migration:
  drop the first argument, `context.sendUpdate(sessionId, update)` becomes
  `context.sendUpdate(update)`; to update another session, call
  `AcpAsyncAgent.sendSessionUpdate(sessionId, update)` (or `AcpSyncAgent.sendSessionUpdate`).
  Test doubles implementing either context implement the one-argument method.

- **Breaking: `handlerExecutor(ExecutorService)` on `AcpAgent.SyncAgentBuilder` and
  `AcpClient.SyncSpec` (and `AcpAgentSupport.Builder` for annotated agents); the static handler
  pools and the agent's default timeout are no longer public constants.** Sync handlers (and a sync client's session update consumers) ran on static,
  unbounded, JVM-wide cached pools exposed as `AcpAgent.SYNC_HANDLER_SCHEDULER` and
  `AcpClient.SYNC_HANDLER_SCHEDULER` (Reactor `Scheduler`s on the entry-point interfaces). Pass an
  executor of your own, for example `Executors.newVirtualThreadPerTaskExecutor()`, a Quarkus worker
  pool or Micronaut's `TaskExecutors.BLOCKING` executor; the SDK cancels a handler by cancelling
  its task (interrupting the thread) and never shuts the executor down. Without one, handlers keep
  running on the same daemon pools as before. `AcpAgent.DEFAULT_REQUEST_TIMEOUT` is removed too;
  the default (60 seconds) is stated on `requestTimeout`. Migration: drop references to
  `AcpAgent.SYNC_HANDLER_SCHEDULER`/`AcpClient.SYNC_HANDLER_SCHEDULER` (use your own scheduler, or
  `handlerExecutor(...)` to choose where handlers run) and replace `AcpAgent.DEFAULT_REQUEST_TIMEOUT`
  with `Duration.ofSeconds(60)`.

- **Breaking: the agent builders reject a null handler and a second handler for the same
  method.** The typed setters of `AcpAgent.AsyncAgentBuilder` and `AcpAgent.SyncAgentBuilder`
  accepted `null` (every request for that method was then answered `-32603`), and registering a
  method again silently replaced its handler. A null handler now fails with
  `IllegalArgumentException` at the setter, and a second registration of the same ACP method (or
  extension method) fails with `IllegalStateException` naming the setter, for example "A handler for
  session/prompt is already registered; promptHandler was called twice on this builder". Migration:
  register each handler once; to choose between handlers, decide before calling the setter.

- **`askPermission` and `askChoice` ask about a tool call the client knows; new
  `askPermission(String action, ToolKind kind)`** on `PromptContext` and `SyncPromptContext`. The
  permission request named a tool call ID that was never announced (clients that look it up in their
  tool-call list found nothing), and `askPermission` labelled every action `edit`. Both helpers now
  announce the tool call first with a `tool_call` session update (status `pending`), ask about it,
  and settle it with a `tool_call_update` (`completed` once the user answered, `failed` if the client
  cancelled the request). `askPermission(action)` uses kind `other`; pass a kind with the new
  overload, for example `askPermission("Run the tests", ToolKind.EXECUTE)`. Breaking (behaviour):
  the session update consumers now see two more updates per call, and a client that showed these
  as edits shows them as `other`. Migration: none needed; to keep the old kind, call
  `askPermission(action, ToolKind.EDIT)`. Implementations of `PromptContext`/`SyncPromptContext`
  (test doubles) add the new overload.

- **Breaking: the sync API throws `AcpTimeoutException` for a timeout, and `CancellationException`
  when interrupted, instead of Reactor's internal `Exceptions$ReactiveException`.** Every blocking
  method of `AcpSyncClient`, `AcpSyncAgent` and `SyncPromptContext` let a request timeout escape as
  `reactor.core.Exceptions$ReactiveException` (cause `TimeoutException`), and `AcpSyncAgent`'s block
  timeout as `IllegalStateException("Timeout on blocking read ...")`. Both now throw the new
  `com.agentclientprotocol.sdk.error.AcpTimeoutException` (an `AcpException`), whose cause is the
  `TimeoutException`. A blocked call whose thread is interrupted (the SDK interrupts a sync handler
  to cancel it) throws `java.util.concurrent.CancellationException` and leaves the interrupt flag
  set; any other checked cause is thrown as an `AcpException`. The asynchronous API is unchanged
  (its `Mono` fails with the `TimeoutException`). Migration: replace
  `catch (RuntimeException e) { if (e.getCause() instanceof TimeoutException) ... }` (or
  `Exceptions.unwrap(e)`) with `catch (AcpTimeoutException e)`, and catch `CancellationException`
  where you handled an interrupt.

- **Breaking (behaviour): a client's prompt is no longer bounded by its request timeout; new
  `promptTimeout(Duration)` on `AcpClient.AsyncSpec` and `AcpClient.SyncSpec`, default none.** A
  prompt's answer comes only at the end of its turn, so the 30-second request timeout cancelled
  every turn that ran longer (the client failed the call with a `TimeoutException` and sent the
  agent `$/cancel_request`, which ended the turn). `session/prompt` now waits for the end of the turn
  however long it takes; every other request keeps the request timeout. Disposing the prompt's
  `Mono` (or interrupting a blocked `AcpSyncClient.prompt`) still cancels it. Migration: to keep a
  bound on turns, set `.promptTimeout(Duration.ofMinutes(10))` (any positive duration; it fails the
  call with a `TimeoutException` and sends `$/cancel_request`, as before); code that raised
  `requestTimeout` only to let long turns finish can drop it. In Spring Boot, set
  `spring.acp.client.prompt-timeout`, in Micronaut `acp.client.prompt-timeout`, in Quarkus
  `quarkus.acp.client.prompt-timeout` (unset by default).

- **Breaking: an annotated agent with `@SetSessionMode` or `@SetSessionConfigOption` needs a
  `@NewSession` method.** The default `session/new` answers with a random session id and no modes
  or config options, so a client never saw any to set and never called those methods. Modes and
  config options are values the agent chooses per session (their ids, names, current values,
  often per client capability), which annotations cannot declare, so the SDK cannot fill them into
  the default answer; building such an agent now fails with an `IllegalStateException` naming the
  setter method and saying "Add a @NewSession method that returns them". **Migration:** add a
  `@NewSession` method returning `new NewSessionResponse(sessionId, modes, configOptions)`.

- **A builder agent answers `initialize` without an initialize handler, and needs a prompt
  handler.** `AcpAgent.sync(..)`/`async(..)` agents with no `initializeHandler` answered
  `initialize` with `-32601` (Method not found), so a prompt-only agent could not even connect.
  They now answer with the protocol version negotiated with the client and the capabilities their
  handlers imply (`loadSessionHandler` → `loadSession`, `listSessionsHandler`/`closeSessionHandler`/
  `resumeSessionHandler`/`deleteSessionHandler`/`forkSessionHandler` → `sessionCapabilities`,
  `logoutHandler` → `auth.logout`, `listProvidersHandler` → `providers`), as an annotated agent
  does, and with the `agentInfo` set by the new builder method `agentInfo(Implementation)`. `build()` now throws `IllegalStateException` when no `promptHandler` is registered, and an
  annotated agent without a `@Prompt` method fails to build the same way: every agent must answer
  `session/prompt`. **Migration:** register a prompt handler (test agents that never prompt can
  answer `PromptResponse.endTurn()`).

- **`AcpAgentSupport.Builder` refuses two silent states.** `build()` and `buildFactory()` throw
  `IllegalStateException` when no `@AcpAgent` bean was given (they used to build an agent with no
  handlers), and `buildFactory()` throws when `transport(..)` was set (the transport used to be
  ignored). **Migration:** pass the bean with `create(bean)`; drop `transport(..)` before
  `buildFactory()`.

- **Annotation misuse fails when the agent is registered or built, not at the first request.**
  `AcpAgentSupport` now rejects, with a message naming the class, the method and the fix:
  - at registration (`create(..)`/`agent(..)`, `IllegalArgumentException`): two methods with the
    same handler annotation (one of them used to win silently), one method with two handler
    annotations, and a `@SessionId`, `@ConfigId` or `@ConfigValue` parameter on an `@ExtRequest`
    or `@ExtNotification` method (it used to receive the extension's params, so every call failed
    `-32602`);
  - at `build()`/`buildFactory()` (`IllegalStateException`): a parameter no argument resolver
    supplies, the request type of another method, `@SessionId` on a method without a session, a
    `PromptContext`/`SyncPromptContext` outside `@Prompt`, `@ConfigId`/`@ConfigValue` outside
    `@SetSessionConfigOption`, a request handler declared `void` (other than `@Prompt`), a return
    type no return value handler accepts, a return type that cannot give the method's response
    (including a `Mono` of another type), and a `void` `@ExtRequest`.
  Each of these used to fail every call with `-32603` or `-32602`. Parameters and return types a
  custom `ArgumentResolver` or `ReturnValueHandler` supports are not checked. Private and static
  handler methods remain supported.
  **Migration:** a build that now fails names the method to fix.

- **`AcpError.getMessage()` no longer ends with `[code=N]`.** It is the peer's message, followed
  by the detail its data carries, so `e.getCode() + " " + e.getMessage()` names the code once.
  `toString()` (what stack traces show) still includes `[code=N]`. Code that parsed the code out of
  the message should call `getCode()`.

- **`StreamableHttpAcpAgentTransportOptions` gains `shutdownTimeout`** (builder
  `shutdownTimeout(Duration)`, default 5 seconds, positive): how long closing the servlet or the
  listener waits for its connections' agents before closing the rest at once. Breaking for code
  calling the record's canonical constructor, which takes it as a new last component; the builder
  is unaffected.

- **0.80.0 is binary-incompatible with 0.18.0: recompile code built against 0.18.0.** Source
  migrates as the Breaking entries below describe, but a jar compiled against 0.18.0 fails at run
  time, typically with `NoSuchMethodError` or `NoClassDefFoundError` from a handler, because several
  canonical constructors and types changed shape. The main ones:
  - `NewSessionResponse`, `LoadSessionResponse`, `ResumeSessionResponse`: `models` is gone and
    `configOptions` comes before `_meta`, so `new NewSessionResponse(id, modes, null)` compiled
    against 0.18.0 calls a constructor that no longer exists.
  - `ToolCall`, `ToolCallUpdate`, `ToolCallUpdateNotification` (`name` after `title`);
    `ClientCapabilities` (`session`, `auth`); `AgentCapabilities` (`auth`); `StringPropertySchema`,
    `MultiSelectPropertySchema` (canonical constructors only); `ElicitationCapabilities` (no-arg
    constructor removed); union records (discriminator component checked).
  - Types replaced or moved: `AuthMethod` (an interface; `AuthMethodAgent`), `ElicitationAction`
    (a record, not an enum), `AcpError` (one `spec.AcpError` instead of the session-nested
    classes), `AcpInvocationContext` and `AcpMethodParameter` (`agent.support.invocation`); the
    `session/set_model` API and the `acp-websocket-jetty` module are removed; `AcpProtocolException`
    no longer converts to or from `JSONRPCError`; `SyncPromptContext.askChoice` returns `Optional`.

  Since this release such an `Error` in a handler is answered `-32603` instead of leaving the peer
  waiting (see Fixed), but the handler still fails until it is recompiled.
- **Breaking: the short constructors of the session updates no longer take the discriminator.**
  It can only be the variant's own name (or `null`), so writing it was noise:
  `new ConfigOptionUpdate(null, options)` is now `new ConfigOptionUpdate(options)`. The new
  constructors replace the old ones:
  - `UserMessageChunk`, `AgentMessageChunk`, `AgentThoughtChunk`: `(content)` and
    `(content, messageId)`.
  - `Plan(entries)`, `AvailableCommandsUpdate(availableCommands)`, `CurrentModeUpdate(currentModeId)`,
    `UsageUpdate(used, size)`, `ConfigOptionUpdate(configOptions)` (the full list of options, not
    only the changed ones).

  The canonical constructors (discriminator first, `null` or the variant's name) are unchanged;
  `SessionInfoUpdate(title, updatedAt)` already had this shape. Migration: drop the first
  argument, for example `new AgentMessageChunk("agent_message_chunk", content)` becomes
  `new AgentMessageChunk(content)`.
- **Breaking: a client's capabilities and info are set only on its builder;
  `initialize(InitializeRequest)` is removed** from `AcpAsyncClient` and `AcpSyncClient`. The
  builder's `clientCapabilities(...)` was used only by the no-argument `initialize()`, and the
  request-taking overload silently replaced it, so a client that set capabilities on the builder
  and then called `initialize(new InitializeRequest(1, new ClientCapabilities()))` advertised
  nothing, while its handlers enforced what the builder said. Now:
  - `AcpClient.async(...)` / `AcpClient.sync(...)`: `clientCapabilities(ClientCapabilities)` (as
    before) and the new `clientInfo(Implementation)`.
  - `initialize()` sends protocol version 1 with the builder's capabilities and client info.
  - `initialize(int protocolVersion, Map<String, Object> meta)` (new) sends a chosen protocol
    version and `_meta` with the same builder values; for `_meta` and version-negotiation tests,
    not for capabilities.

  Migration: move the request's capabilities to `.clientCapabilities(...)` and its `clientInfo`
  to `.clientInfo(...)`, then call `initialize()`; pass `_meta` through `initialize(1, meta)`. A
  client that passed `new InitializeRequest(1, null)` now advertises the default
  `new ClientCapabilities()` (no file system, no terminal) instead of omitting
  `clientCapabilities`.
- **Quieter logs for errors the caller already receives, and a named logger for agent stderr.**
  - A peer's JSON-RPC error response (for example a handled `-32602`) was logged at ERROR
    ("Error handling request") and then failed the caller's request with the same error. The
    caller's `AcpError` is the report; the session now logs it at DEBUG only, as
    `Request <method> failed with peer error <code>`, without the error's message or data (the
    peer's payload). Applications that relied on the ERROR line should log the `AcpError` they
    receive.
  - `StdioAcpClientTransport` logs each line the agent writes to its standard error at INFO on its
    own logger, `com.agentclientprotocol.sdk.client.transport.agent-stderr`
    (`StdioAcpClientTransport.AGENT_STDERR_LOGGER`), as `agent: <line>`. Before, it used the
    transport's logger with the prefix `STDERR Message received: `. Set that logger to WARN to
    hide the agent's output, or replace it with `setStdErrorHandler(...)`.
- **Breaking: error codes follow the ACP v1 schema (`$defs.ErrorCode`).** A prompt sent while the
  session already has an active prompt was rejected with `-32000`, which ACP defines as
  "Authentication required", so a client could ask its user to log in when the user had only sent a
  second prompt. It is now rejected with `-32600` (invalid request), message
  `There is already an active prompt on session <id>` and data `{"sessionId": "<id>"}`, as the Rust
  SDK rejects requests that are invalid in the connection's state; the Kotlin SDK answers
  `-32603`. `AcpErrorCodes` keeps only codes the schema defines:
  - `AUTHENTICATION_REQUIRED` is `-32000` (it was `-32004`); `AcpProtocolException.isAuthenticationRequired()`
    is new.
  - `SESSION_NOT_FOUND` (`-32002`) is renamed `RESOURCE_NOT_FOUND`, the schema's name for the code.
  - `CONCURRENT_PROMPT` (`-32000`) and `AcpProtocolException.isConcurrentPrompt()` are removed; a
    concurrent prompt is an `INVALID_REQUEST`.
  - `CAPABILITY_NOT_SUPPORTED` (`-32001`), `NOT_INITIALIZED` (`-32003`) and `PERMISSION_DENIED`
    (`-32005`) are removed: the SDK made them up inside the range ACP reserves for itself.
    `AcpCapabilityException.toProtocolException()`, which answered `-32001`, is removed (see
    Removed).
- **Elicitation is stable (the ACP schema promoted it on 2026-07-24, in protocol v1.7.0), and its
  API is no longer `@UnstableAcpApi`:** `CreateElicitationRequest`, `CreateElicitationResponse`,
  `ElicitationAction`, `CompleteElicitationNotification`, `ElicitationSchema`, the property schemas,
  the multi-select items, `EnumOption`, `ElicitationCapabilities` and
  `ClientCapabilities.elicitation`.
- **Breaking: the elicitation capability's modes are typed, and the no-argument
  `ElicitationCapabilities()` constructor is removed.** `form` and `url` were `Object`; they are now
  `ElicitationFormCapabilities` and `ElicitationUrlCapabilities` (each with `_meta`), as in the
  schema. The removed constructor advertised form mode, although the spec says an empty capability
  (`{}`) advertises no mode. Migration: `new ElicitationCapabilities()` becomes
  `ElicitationCapabilities.formOnly()`; `urlOnly()` and `formAndUrl()` advertise the other modes, and
  code that read `form()` or `url()` as `Object` gets the typed records.
- **Breaking: `ElicitationAction` is an open value, not an enum,** by the rule for the schema's
  closed string unions (see the `AcpSchema` Javadoc): a record over the wire string with the
  constants `ACCEPT`, `DECLINE` and `CANCEL`, `of(String)`, `known()` and `isKnown()`. An action
  this SDK does not know (a newer peer) is kept and written back instead of failing the response.
  Migration: compare with `equals`, not `==`, and replace a `switch` over the enum with `if`/`else`
  or a switch over `action().value()`.
- **Elicitation form properties of an unknown type and multi-select items of an unknown shape no
  longer fail the request.** A property of an unknown type reads as
  `UnknownElicitationPropertySchema`, keeping `type` and every other field, and is written back
  unchanged, as for the other discriminated unions. Multi-select items have no shared
  discriminator, so the SDK tells their shapes apart by their members: items with a `type` or an
  `enum` member read as `UntitledMultiSelectItems` and items with an `anyOf` member as
  `TitledMultiSelectItems`, whatever the `type`, and lose the members that record does not have.
  Only items with none of these members read as `UnknownMultiSelectItems`, which keeps every
  field and writes it back unchanged. The five property schemas now write their own `type` discriminator:
  their canonical constructors accept `null` for it (it becomes the record's type) and reject a
  different type, which they used to accept and ignore.
- **Breaking: `StringPropertySchema` and `MultiSelectPropertySchema` have only their canonical
  constructors,** which end with `_meta`. Migration: pass `null` as the last argument, or use
  `StringPropertySchema.text(...)` and `singleSelect(...)`.
- **Behaviour change: the agent checks the requested elicitation mode, not only elicitation.**
  `createElicitation` fails with `AcpCapabilityException` (`elicitation.form` or `elicitation.url`)
  when the client did not advertise the request's mode; before, any `elicitation` object, even `{}`,
  let both modes through. The spec says agents must not request a mode the client did not advertise.
  A mode the SDK does not know still needs only `elicitation`.
- **Behaviour change: a client with a typed `createElicitationHandler` answers a request for a mode
  it did not advertise at initialization with `-32602` (invalid params)**, as the spec says, without
  calling the handler. Before initialization, and for a mode the SDK does not know, the handler is
  called. Migration: advertise the modes the handler supports in `clientCapabilities.elicitation`.
  A raw `requestHandler("elicitation/create", ...)` is not checked.

- **Unknown union variants no longer fail the message** (ACP forward compatibility). A newer agent's
  `sessionUpdate` type used to fail the whole `session/update` notification, which the client then
  dropped with an error log; an unknown content block, tool call content type, config option type
  or permission outcome failed its message the same way. Each union now reads an unknown variant as
  its `Unknown*` record (`UnknownSessionUpdate`, `UnknownContentBlock`, `UnknownToolCallContent`,
  `UnknownSessionConfigOption`, `UnknownPermissionOutcome`), which keeps the discriminator and
  every other field and writes them back unchanged. Session update consumers receive it like any
  other update; code that does not know the variant ignores it. The Rust, TypeScript and Python
  SDKs drop such a notification; the Kotlin SDK and the v2 schema keep the raw payload, which this
  SDK follows. The rule is documented on `AcpSchema`. Unions with a default variant keep it
  (`McpServer` reads an unknown `type` as stdio).
- **Breaking: a select config option's `options` is flat or grouped.** The schema's
  `SessionConfigSelectOptions` is a list of options or a list of groups
  (`SessionConfigSelectGroup`: `group`, `name`, `options`, `_meta`); a grouped list failed to read.
  `SessionConfigSelect.options()` now returns `SessionConfigSelectOptions`, either
  `UngroupedSelectOptions` or `GroupedSelectOptions`, both written as the JSON array they read from;
  `allOptions()` lists every option across groups, and a list mixing options and groups is rejected.
  Migration: `select.options()` used as a list becomes `select.options().allOptions()` (or match on
  the two records); the canonical constructor takes `SessionConfigSelectOptions.ungrouped(list)` or
  `grouped(groups)`; the `(id, name, currentValue, List<SessionConfigSelectOption>)` constructor is
  unchanged.
- **Breaking: unknown enum values no longer fail the message; most schema enums are open value
  types.** A newer peer's stop reason, tool call status, permission option kind, plan entry status
  or priority, or annotation role failed deserialization of the whole message (a prompt's
  response included). `StopReason`, `ToolCallStatus`, `PermissionOptionKind`, `PlanEntryStatus`,
  `PlanEntryPriority` and `Role` are now records over the wire string: the constants keep their
  names (`StopReason.END_TURN`), `of(String)` returns the constant for a known value, an unknown
  value is kept and written back (`isKnown()` false), `known()` lists the defined values and
  `toString()` is the wire value. `ToolKind` stays an enum and reads an unknown kind as `OTHER`,
  the schema's catch-all (as in the Rust SDK); it gains `value()` and `of(String)`. The rule is
  documented on `AcpSchema`. Migration: compare with `equals` instead of `==` (a value built with
  `new` is not the constant); replace `switch` over these types with `if`/`equals` or a switch on
  `value()`; `values()` becomes `known()`, `name()` becomes `value()` (the wire name, e.g.
  `end_turn`, not `END_TURN`).
- **Breaking: union records write their own discriminator, and reject a wrong one.** Every union
  above is now declared `include = EXISTING_PROPERTY`, so the `sessionUpdate`, `type` or `outcome`
  component of a record such as `AgentMessageChunk` or `TextContent` is written as is. Before,
  Jackson replaced whatever the caller passed with the registered name. A canonical constructor
  now turns `null` into the record's own name and throws `IllegalArgumentException` for any other
  value (`new AgentMessageChunk("agentMessage", ...)` used to be silently corrected). Migration:
  pass the variant's wire name (`"agent_message_chunk"`) or `null`, or use the convenience
  constructors, which already do. Code that switches exhaustively over a union's known records
  needs a branch for its `Unknown*` record.
- **Breaking: the canonical constructors of `NewSessionResponse`, `LoadSessionResponse` and
  `ResumeSessionResponse` take `configOptions` before `_meta`.** `new NewSessionResponse(id, modes,
  null)` and `new LoadSessionResponse(modes, null)` still compile (the `null` is now the config
  options); a call that passed a `_meta` map in that position no longer compiles. Migration: pass
  `(id, modes, configOptions, meta)` or `(modes, configOptions, meta)`.
- **Breaking: `ToolCall`, `ToolCallUpdate` and `ToolCallUpdateNotification` take `name` after
  `title`.** Migration: insert the tool's name, or `null`, after the title argument of the
  canonical constructor.
- **Breaking: `AuthMethod` is an interface; the agent method is `AuthMethodAgent`.** The schema
  makes `AuthMethod` a union (no `type`: agent; `"terminal"`: terminal). A method of any other
  type reads as `AuthMethodAgent`, as in the Rust SDK. Migration: `new AuthMethod(id, name,
  description)` becomes `new AcpSchema.AuthMethodAgent(id, name, description)`; `id()`, `name()`
  and `description()` stay on the interface. `AuthMethodAgent` also carries `_meta`.
- **Breaking: `ClientCapabilities` takes `session` and `auth` after `terminal`** in its canonical
  constructor, in schema order. Migration: `new ClientCapabilities(fs, terminal, elicitation,
  meta)` becomes `new ClientCapabilities(fs, terminal, session, auth, elicitation, meta)` (both
  may be `null`).
- **Breaking: `AgentCapabilities` takes `auth` after `promptCapabilities`** in its canonical
  constructor. Migration: `new AgentCapabilities(loadSession, session, mcp, prompt, providers,
  meta)` becomes `new AgentCapabilities(loadSession, session, mcp, prompt, auth, providers, meta)`
  (`auth` may be `null`); the shorter constructors are unchanged.
- **Behaviour change: a request whose caller gives up is cancelled at the peer.** Disposing a
  request `Mono`, or its timeout firing (the client's default request timeout is 30 seconds, the
  agent's 60), now sends `$/cancel_request`, so a peer that supports it stops the work and answers
  `-32800`. A `session/prompt` that times out on the client is therefore cancelled at an agent that
  supports `$/cancel_request` (the Java agent answers it at once and ends the turn), where before
  the agent kept running the turn. Migration: raise `requestTimeout` for long prompts, or send
  `session/cancel` for the graceful prompt cancel with stop reason `cancelled`.
- **Fix: a resubscribed request is a new request.** Subscribing twice to one `sendRequest` `Mono`
  (a `retry()`, say) reused the request id, so the first response completed both. Each
  subscription now gets its own id.
- **Behaviour change: `session/cancel` no longer ends the prompt turn; the cancelled prompt's
  response does.** The agent session used to free the session for a new prompt as soon as the
  cancel notification arrived, so a client could start a second prompt while the cancelled one's
  handler was still running. ACP v1 (prompt turn, Cancellation) says the agent may still send
  `session/update`s after the cancel and must then answer the original `session/prompt` with stop
  reason `cancelled`, and "once a prompt turn completes, the Client may send another
  `session/prompt`". A prompt sent between the cancel and that answer is now rejected with
  `-32600` (invalid request; see below), like any prompt during an active turn. The turn ends when the
  answer is published (before it reaches the client, as for every prompt), when the handler fails
  (a timeout the handler applies included), when the request is cancelled, when the session
  closes, or when the cancel grace period passes (below). Migration: clients send the next prompt
  after the cancelled one has answered (the SDK client sends nothing on its own after `cancel`);
  agent prompt handlers must answer a cancelled prompt.
- **New default: a cancelled prompt is answered within 60 seconds.** If the prompt handler has not
  answered 60 seconds after `session/cancel`, the agent cancels the handler's subscription and
  answers the prompt with stop reason `cancelled` itself (the answer ACP v1 requires of a
  cancelled prompt), which ends the turn. Before, a handler that never answered a cancelled prompt
  kept its session busy for good. `cancelGracePeriod(Duration.ZERO)` restores that. The public
  four-argument `AcpAgentSession` constructor uses the same default.
- **Breaking (unstable providers API): the provider identifier is `providerId`, on the wire and in
  Java.** `ProviderInfo`, `SetProviderRequest` and `DisableProviderRequest` wrote and read `"id"`,
  but the unstable schema names the property `providerId`, so no spec-conforming peer could exchange
  them with the SDK. The record component is renamed with it. Migration: `id()` becomes
  `providerId()`; the constructors keep their parameter order.
- **Breaking: `AcpProtocolException` no longer converts to or from `JSONRPCError`.** Use
  `AcpSchema.JSONRPCError.from(exception)` instead of `exception.toJsonRpcError()`, and
  `error.toException()` instead of `new AcpProtocolException(error)`. Dependencies between the `spec` and
  `error` packages now run one way.
- **Every package is null-marked (JSpecify `@NullMarked`), and the nullness is now part of the API.**
  Types are non-null unless annotated `@Nullable`, and the annotations say where absence is legal:
  `AcpSchema` record components follow the ACP schema (optional and nullable fields are `@Nullable`,
  217 of them; required ones are not), as do the JSON-RPC envelope (`id`, `params`, `result`, `error`,
  `data`), `AcpJsonMapper.readValue` (the JSON `null` literal), `getClientCapabilities()` and
  `getAgentCapabilities()` (before initialization), `AcpProtocolException.getData()`, and the
  annotation-driven support types (resolved arguments, handler return values, interceptor results).
  Kotlin and other nullness-aware callers see these types; Java callers compile unchanged.
- **Breaking: `SyncPromptContext.askChoice` returns `Optional<String>`**, empty when the client cancels
  the choice (it was documented to return null, and failed instead, below). `PromptContext.askChoice`
  completes empty on cancellation. Migration: `askChoice(...).orElse(...)`, or test `isPresent()`.
- **Breaking: `SyncPromptContext` has an abstract `async()` method**, returning the `PromptContext` it
  blocks on (the same session, turn and client). Migration: an implementation of `SyncPromptContext`,
  such as a test double, implements `async()`.
- **Breaking: `CommandResult` carries a nullable exit code and the terminating signal, and no
  `timedOut` flag.** Its components are `(String output, @Nullable Integer exitCode,
  @Nullable String signal)`: a command killed by a signal has no exit code. `timedOut` is removed,
  with the `(output, int exitCode, boolean timedOut)` constructor: `execute` has no timeout (neither
  `Command` nor `terminal/wait_for_exit` has one), so it never set the flag, and a result could not
  report a timeout truthfully. The `(output, int exitCode)` constructor remains. Migration:
  `exitCode()` returns `Integer`; use `success()`, or check `exitCode()` for null before comparing
  it. To bound a command, use the terminal methods (`createTerminal`, `killTerminal`) directly.
- **Breaking: `Command.env()` is never null**; it is empty when no variables are set (`Command.of`
  now builds it with `Map.of()`, and the canonical constructor takes a non-null map). Migration: test
  `env().isEmpty()` instead of `env() == null`.
- `AcpException.getMessage()` is declared non-null: every constructor sets a message.
- `StdioAcpClientTransport.awaitForExit()` before `connect` throws `IllegalStateException` instead of
  `NullPointerException`.
- `AcpInvocationContext.Builder.build()` requires `acpMethod` and `request`.
- **Breaking: `AcpInvocationContext` and `AcpMethodParameter` moved to
  `com.agentclientprotocol.sdk.agent.support.invocation`.** The resolver, handler and interceptor
  packages used them from `agent.support`, which in turn used those packages, so the four packages
  formed a cycle. They now depend on the `invocation` package and `AcpAgentSupport` depends on them,
  one way. Migration: change the imports in custom `ArgumentResolver`, `ReturnValueHandler` and
  `AcpInterceptor` implementations to `com.agentclientprotocol.sdk.agent.support.invocation.*`.
- **Breaking: the error a request fails with when the peer answers with a JSON-RPC error is one
  type, `com.agentclientprotocol.sdk.spec.AcpError`,** on both sides. It replaces the two identical
  nested classes `AcpClientSession.AcpError` and `AcpAgentSession.AcpError`; its constructor,
  message, `getError()`, `getCode()` and `getData()` are unchanged. Migration: catch or test for
  `AcpError` (import `com.agentclientprotocol.sdk.spec.AcpError`) instead of
  `AcpClientSession.AcpError` or `AcpAgentSession.AcpError`.
- `com.agentclientprotocol.sdk.util.OutboundSinks` is new: the emission onto a transport's outbound
  sink that retries while another thread emits (#14), and the reply pump from inbound messages
  through the session handler to that sink, which the stdio and WebSocket transports shared as
  copies.
- **`@UnstableAcpApi` marks all of the unstable `session/fork` and `providers/*` API.** Those methods
  are not in the stable schema, and their records, annotations and provider handlers were marked,
  but `METHOD_SESSION_FORK` and `METHOD_PROVIDERS_*`, `AcpAgent.ForkSessionHandler` and
  `SyncForkSessionHandler`, both builders' `forkSessionHandler`, `AcpAsyncClient` and
  `AcpSyncClient` `forkSession`, `listProviders`, `setProvider` and `disableProvider`,
  `NegotiatedCapabilities` `supportsForkSession`, `supportsProviders`, `requireForkSession`,
  `requireProviders` and the builder's `forkSession` and `providers`, and the four request
  resolvers were not. They are now; nothing else changes. Tests derive the unstable methods from
  the stable schema copy and fail when a public member serving one lacks the marker, or a stable
  method's member carries it.

- **Breaking: `SessionConfigSelect.Builder.build()` checks the option's value.** It now throws
  `IllegalStateException` when the options (across all groups) are empty, or when `currentValue`
  is not the value of one of them, instead of building an option no client can display correctly.
  The record's constructors stay lenient, since they also read other agents' options.
  **Migration:** pass at least one option and set `currentValue` to one of their values.

- **Breaking (behaviour): one default request timeout, 60 seconds.** The client builders
  (`AcpClient.sync/async`) and `AcpAgentSupport.Builder` defaulted to 30 seconds while the agent
  builders defaulted to 60; all now wait 60 seconds for an answer unless `requestTimeout` is set.
  60 seconds because the agent's requests (a permission prompt, an elicitation) wait on a person,
  and a client's `session/new` may wait for the agent to start its MCP servers.
  `AcpAgentSupport.Builder.requestTimeout` now accepts `null`, meaning the SDK default, and an
  annotated agent no longer restates the default. The Spring Boot property
  `spring.acp.client.request-timeout` and the Micronaut setting `acp.client.request-timeout`
  default to 60 s as well; Quarkus's `quarkus.acp.client.request-timeout`, unset, already follows the
  SDK. **Migration:** none needed; to keep the old bound, set
  `requestTimeout(Duration.ofSeconds(30))` (or the property to `30s`).

- **Breaking (behaviour): a client handler is registered once.** On `AcpClient.AsyncSpec` and
  `SyncSpec`, registering a second handler for a method (a typed setter such as
  `readTextFileHandler` called twice, `requestHandler`, `notificationHandler`,
  `extRequestHandler`/`extNotificationHandler` for a method that already has one, or a raw handler
  and the typed setter for the same method) now throws `IllegalStateException` naming the setter,
  instead of silently replacing the first. A null handler still throws `IllegalArgumentException`.
  `sessionUpdateConsumer` stays additive. **Migration:** register each method once; to compose
  behaviour, do it inside one handler.

- **Breaking: the raw client handlers are for methods the SDK does not model.**
  `AcpClient.AsyncSpec`/`SyncSpec.requestHandler(method, ..)` now throws `IllegalArgumentException`
  for a method that has a typed setter (`fs/read_text_file`, `fs/write_text_file`,
  `session/request_permission`, the five `terminal/*` methods, `elicitation/create`), and
  `notificationHandler(method, ..)` for `session/update` and `elicitation/complete`, naming the
  setter to use: the raw handler bypassed the typed params and the elicitation mode check, and a
  raw `session/update` handler was silently replaced by the session update consumers.
  `SyncSpec.notificationHandler` now takes a blocking `Consumer<Object>` run on the sync handler
  threads, like the sync builder's other handlers, instead of the async `NotificationHandler`
  returning a `Mono`. **Migration:** use the typed setter the exception names; on the sync
  builder, drop the `Mono` from a raw notification handler (`params -> { ... }`).

- **Breaking (behaviour): a client's advertised capabilities must have their handlers.**
  `AcpClient.AsyncSpec`/`SyncSpec.build()` now throws `IllegalStateException` when the client
  capabilities advertise `fs.readTextFile` without `readTextFileHandler`, `fs.writeTextFile`
  without `writeTextFileHandler`, `terminal` without all five terminal handlers
  (`createTerminalHandler`, `terminalOutputHandler`, `releaseTerminalHandler`,
  `waitForTerminalExitHandler`, `killTerminalHandler`), or an elicitation mode without
  `createElicitationHandler`; the message names every missing setter. Such a client used to build,
  and the agent's requests were answered "Method not found". A handler for a capability the client
  does not advertise logs one WARN, since an SDK agent will not call it. The Spring Boot
  (`spring.acp.client.capabilities.*`), Micronaut (`acp.client.capabilities.*`) and Quarkus
  (`quarkus.acp.client.capabilities.*`) client beans add the setting that advertised the capability
  to the message. The test `MockAcpClient` no longer
  advertises `terminal`, which it never served. **Migration:** register the handlers (in an
  `AcpClientCustomizer` when the capabilities come from properties), or stop advertising the
  capability.

- **Breaking (behaviour): a client initializes first, and calls only what the agent advertised.**
  Every `AcpAsyncClient`/`AcpSyncClient` ACP call but `initialize` now fails with
  `IllegalStateException` ("Call initialize() first") until the agent has answered `initialize`;
  it used to be sent. `loadSession`, `listSessions`, `closeSession`, `deleteSession`,
  `resumeSession`, `forkSession`, `logout` and the `providers/*` calls fail with
  `AcpCapabilityException` (naming the capability, such as `sessionCapabilities.close`) when the
  agent's `initialize` answer did not advertise it, as the agent side already does for the
  client's capabilities. Neither sends anything. The check runs when the call is subscribed, so
  `client.initialize().then(client.newSession(..))` works. Extension methods (`sendExtRequest`,
  `sendExtNotification`) are outside ACP's lifecycle and are not checked. **Migration:** call
  `initialize()` first; check `getAgentCapabilities()` before an optional call, or catch
  `AcpCapabilityException`. A test agent with its own `initializeHandler` must advertise the
  capabilities its handlers serve (or drop the handler: a builder agent's default `initialize`
  advertises them).

### Removed

- **Breaking: `AcpCapabilityException.toProtocolException()` is removed.** It answered `-32600`
  (invalid request) for a missing capability, while the one refusal ACP specifies, a client asked
  for an elicitation in a mode it did not declare, is `-32602` (invalid params; ACP's elicitation
  RFD, "Error Handling"), which the SDK's client sends. The SDK never called the method, so it
  only offered a second code for the same case. Migration: throw
  `new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, e.getMessage(), e.getCapability())`.

- **Breaking: `AcpAgent.logger` and `AcpClient.logger` are removed.** As fields of public
  interfaces they were public `org.slf4j.Logger` constants, API by accident. Migration: use a
  logger of your own (`LoggerFactory.getLogger(MyAgent.class)`); to see the client builders'
  session-update logging, configure the logger named `com.agentclientprotocol.sdk.client.AcpClient`
  as before.

- **Breaking: `PromptResponse.text(String)` is removed, and a `@Prompt` method's String now reaches
  the client.** `text(String)` threw its text away and returned the same response as `endTurn()`: a
  prompt response carries no content, and a static factory cannot send a session update. So an
  annotated `@Prompt` method that returned a `String` (documented as "converted to
  `PromptResponse.text()`") ended the turn and sent nothing. That String is now sent to the client
  as an `agent_message_chunk` session update of the prompt's session, then the turn ends with
  `end_turn`; a null or empty String sends nothing. Migration: replace
  `return PromptResponse.text(message);` with `context.sendMessage(message); return
  PromptResponse.endTurn();` (`SyncPromptContext` or, in an async handler, `PromptContext`), or,
  in a `@Prompt` method, return the String itself.

- **Breaking: the session-model API (`session/set_model`) is removed.** Deprecated for removal in
  0.14.0; the ACP schema 1.9.1 (stable and unstable) no longer defines the method, its request and
  response, or the `models` field on session responses. Removed: `AcpSchema.METHOD_SESSION_SET_MODEL`,
  `SetSessionModelRequest`, `SetSessionModelResponse`, `SessionModelState`, `ModelInfo`; the `models`
  component of `NewSessionResponse`, `LoadSessionResponse`, `ResumeSessionResponse` and
  `ForkSessionResponse` (and the convenience constructors that took it: they now take the session ID
  and/or `modes` only); `AcpAgent.SetSessionModelHandler`, `SyncSetSessionModelHandler` and the
  builders' `setSessionModelHandler`; `AcpAsyncClient.setSessionModel` and
  `AcpSyncClient.setSessionModel`; the `@SetSessionModel` annotation and
  `SetSessionModelRequestResolver`; the method's Streamable HTTP routing entries. An agent no longer
  answers `session/set_model` (a peer that sends it gets "method not found"). Migration: expose model
  selection as a session config option whose `category` is `"model"`, and change it with
  `session/set_config_option` (`setSessionConfigOptionHandler`, `@SetSessionConfigOption`,
  `AcpAsyncClient.setSessionConfigOption`).
- **Breaking: the `acp-websocket-jetty` module and its `WebSocketAcpAgentTransport` are removed.**
  Deprecated for removal in 0.18.0; the module held only that class, a WebSocket agent transport
  that served a single client. Migration: depend on `acp-streamable-http-jetty` and serve agents with
  `StreamableHttpAcpAgentTransport`, which accepts WebSocket upgrades at `/acp` (and the Streamable
  HTTP profile on the same path) and creates one agent per connection through an `AcpAgentFactory`:
  replace `AcpAgent.async(new WebSocketAcpAgentTransport(port, mapper))...build()` with
  `new StreamableHttpAcpAgentTransport(port, mapper, AcpAgentFactory.async(t -> AcpAgent.async(t)...build()))`.
  `WebSocketAcpClientTransport` (in `acp-core`) is unchanged and connects to it as before.
- **Breaking: the `@SessionState` and `@AcpExceptionHandler` annotations are removed.** Both were
  declared in `acp-annotations` but nothing implemented them: a `@SessionState` parameter failed
  every call with "No resolver for parameter", and an `@AcpExceptionHandler` method was never
  called. Migration: keep per-session state in the agent, in a thread-safe map keyed by the
  session ID (an `@SessionId String` parameter or the request's `sessionId()`), and remove it in
  the `@CloseSession`/`@DeleteSession` handler; handle exceptions in the handler method itself, or
  in an `AcpInterceptor`'s `onError`, and throw `AcpProtocolException` to answer with a specific
  JSON-RPC error.

### Fixed

- **Spring Boot: `spring.acp.agent.enabled=false` turns the agent off on every transport.** The
  stdio transport and the HTTP endpoint already backed off, but `AcpAgentAutoConfiguration` did
  not: it still built the `AcpAgentFactory` from the `@AcpAgent` bean and still served the agent on
  an `AcpAgentTransport` bean the application defined itself. With `enabled=false` it now creates
  no agent beans and serves nothing, as Micronaut and Quarkus already did.

- **Spring Boot: two `@AcpAgent` beans no longer fail the startup when
  `spring.acp.agent.enabled=false`.** The startup failed with the error naming both beans, whose
  advice was to set `spring.acp.agent.enabled=false`. Same cause and fix as the entry above.

- **Spring Boot: `spring.acp.agent.transport.type=http` in a reactive (WebFlux) web application
  fails the startup.** It served no endpoint and gave no error. The startup now fails with "The
  ACP HTTP transport needs a servlet web application or the standalone listener
  (acp-streamable-http-jetty); WebFlux is not supported".

- **A prompt answers `cancelled` once `session/cancel` was received, whatever its handler
  returns.** ACP requires an agent to answer a cancelled prompt with stop reason `cancelled`. A
  handler that noticed the cancel and returned another stop reason within the grace period had
  that stop reason sent: a `void`, `String`, `Mono<String>` or `CompletionStage<String>` `@Prompt`
  method sent `end_turn`, and so did a builder handler returning `PromptResponse.endTurn()`. The
  agent session now sends a handler's `PromptResponse` with stop reason `cancelled` once
  `session/cancel` was received for that prompt, keeping its `_meta`, and logs the change at
  DEBUG. It uses the prompt's existing cancelling state, the one the cancel grace period uses.
  Behaviour change; migration: a handler that returned another stop reason after cancel now sends
  `cancelled`.

- **A prompt that fails after `session/cancel` answers `cancelled`, not an error.** ACP requires
  agents to catch the errors aborted work raises and answer `cancelled`. Only a cancellation
  (`CancellationException`, an interrupt, an `AcpProtocolException` with `-32800`) was answered
  `cancelled`; any other exception, such as the one a model client throws when its call is aborted,
  went out as `-32603`. Once `session/cancel` was received for the prompt, any failure is now
  answered `{"stopReason":"cancelled"}` and logged at DEBUG. Before a cancel, failures are answered
  as before. Behaviour change; covered by the migration line above.

- **A builder agent answers `session/new` without a new-session handler.** ACP requires every
  agent to support `session/new`; a builder agent built without `newSessionHandler` answered it
  `-32601`, so no client could open a session. It now answers with a random UUID as the session ID
  and no modes or config options, as an annotated agent without a `@NewSession` method already did.
  Both use the new `AcpSchema.NewSessionResponse.withGeneratedId()`. A registered handler replaces
  the default, and `build()` still requires only a prompt handler.

- **The agent checks the client's `terminal` capability for every terminal method.** ACP forbids
  an agent to call any terminal method when the client did not advertise `terminal`. Only
  `terminal/create` was checked; `terminal/output`, `terminal/wait_for_exit`, `terminal/kill` and
  `terminal/release` were sent regardless, for example with a terminal ID from another connection.
  All five now fail locally with `AcpCapabilityException` and send nothing, from the agent and
  from a prompt context.

- **A prompt's updates no longer follow its answer.** ACP requires an agent to send a prompt's
  `session/update` notifications before it answers the prompt, also after `session/cancel`. When
  the SDK answered a prompt itself (the cancel grace period or `maxPromptDuration` passed), a
  handler that kept running could still send updates through its prompt context, and they went out
  after the answer; so could a handler that sent through its context after returning its own
  answer. Once a prompt has been answered, by its handler or by the SDK, `PromptContext.sendUpdate`
  and `SyncPromptContext.sendUpdate` (and the helpers built on them, such as `sendMessage`) drop
  the update and log it at DEBUG, without its content; the `Mono` completes empty. The check uses
  the prompt's answered-once state that decides who answers. Updates sent with
  `sendSessionUpdate` outside a prompt context are not affected.

- **A builder agent advertises `providers` for any provider handler.** Without an initialize
  handler, a builder agent advertised `providers` only for a `providers/list` handler, while an
  annotated agent does for any of the three provider methods; with only a set or disable handler
  the client refused those calls. It now advertises `providers` for any of them.

- **`SessionCapabilities` writes only objects, and reads only objects as advertised.** Its
  components are typed `Object`: `Boolean.TRUE` wrote `"list":true`, which the schema forbids (a
  session capability is an object), and on reading any non-null value counted as advertised, so a
  peer's `"close": false` made `supportsCloseSession()` true. The record now holds `Boolean.TRUE`
  as `{}` and `Boolean.FALSE` as absent, and reading JSON counts only an object as advertised; a
  `true`, `false` or other value reads as not advertised. The component types are unchanged.

- **The `acp-agent-support` README's custom return value handler example no longer breaks
  `CompletableFuture`.** The example registered a `CompletableFutureHandler`; custom handlers are
  asked before the built-in ones, so it replaced the built-in `CompletionStage` support and a
  `CompletableFuture<String>` from `@Prompt` failed every prompt with `-32603`. The example now
  adds a type the SDK does not handle (a plain `Future`), and a test keeps it in step with the
  README. The README's interceptor example no longer says `afterCompletion` is always called: it
  is called for the interceptors whose `preInvoke` returned `true`.

- **`AcpAgentSupport.Builder.interceptor(null)` fails at once** with `IllegalArgumentException`,
  as `argumentResolver(null)` and `returnValueHandler(null)` do. It was accepted, and the next
  build failed with a bare `NullPointerException`.

- **An `Error` while reading a stdio agent's output is reported as itself.** When handling a line
  of the agent's standard output threw an `Error` (typically a `NoSuchMethodError` from a Jackson
  version clash), `StdioAcpClientTransport` ended as if the agent had "closed its standard output
  but has not exited". The real cause is now logged, passed to the exception handler, and is the
  cause of the `AcpConnectionException` that ends the transport ("Reading the agent's output
  failed: ...").

- **A message sent after a transport closed fails instead of being dropped.**
  `StdioAcpClientTransport`, `StdioAcpAgentTransport` and `WebSocketAcpClientTransport` dropped a
  message sent once they were closed and completed its `Mono`, so a request then waited out its
  timeout; `OutboundSinks.emit` documented a failure it never raised (Reactor's `emitNext` drops a
  value on a terminated sink). `sendMessage` now fails with `AcpConnectionException` ("The
  transport is closed"), as `StreamableHttpAcpClientTransport` already did, and `OutboundSinks.emit`
  throws `Sinks.EmissionException` with `FAIL_TERMINATED` as documented.

- **`WebSocketAcpClientTransport` shuts down its own HTTP client's threads on close.** A transport
  built without an `HttpClient` creates one with a cached thread pool, which was never shut down,
  so its threads lingered for about a minute after close. Closing the transport now shuts that
  executor down; an `HttpClient` you pass in is left alone.

- **A `WebSocketAcpClientTransport` whose connect failed can connect again.** The failed `connect`
  had already subscribed the transport's single-subscriber inbound sink, so a retry on the same
  transport "succeeded" but no message from the agent ever reached the handler (Reactor logged
  "unicast() sinks only allow a single Subscriber"). The inbound side is now subscribed only once
  the connection is open.

- **`CommandResult` carries the terminal output's `truncated` flag** (new last record component
  `truncated`, accessor `truncated()`). `execute` dropped the flag of the client's
  `terminal/output` answer, so a caller could not tell output cut to the command's output limit
  from complete output. Breaking only for code calling the canonical constructor
  `new CommandResult(output, exitCode, signal)`, which still exists as a convenience constructor
  (complete output); the new canonical form is
  `new CommandResult(output, exitCode, signal, truncated)`.

- **`SyncPromptContext.tryReadFile` no longer swallows the cancelling interrupt.** It returned
  empty for every failure, including the interrupt the SDK uses to cancel a sync handler, and the
  interrupt flag was lost, so a cancelled handler carried on as if the file were missing. It now
  throws `CancellationException` (interrupt flag set) when the handler is cancelled while it waits,
  as the other calls do; any other failure still gives empty.

- **`askChoice` fails clearly when the client answers with an option it was not offered.** It
  parsed the chosen option ID as an index, so an unexpected answer failed with
  `NumberFormatException` or `ArrayIndexOutOfBoundsException`. It now fails with an
  `AcpError` (`-32603`) naming the ID, with data `{"reason": "unoffered-option", "method":
  "session/request_permission", "optionId": <id>}`, as any answer the SDK rejects does; a cancelled
  request still gives an empty result.

- **A request from the agent reaches its client handler only after the session updates sent
  before it.** Requests were handled as they arrived, outside the ordered notification drain, so a
  `session/request_permission` could reach its handler while the session update consumers had not
  yet seen the `tool_call` update the agent sent just before it, and the client could not look the
  tool call up. A request now takes its place in the drain like a response: it is dispatched once
  the notifications before it have been handled (handlers still run concurrently with each other
  and with later updates). To keep a consumer that waits for an answer of its own from deadlocking,
  held requests are dispatched at once while the running consumer waits for a response. A
  `$/cancel_request` for a request still held answers it `-32800` without calling its handler.
  Model checked with Lincheck (`InboundOrderLincheckTest`).

- **`PromptContext.execute`/`SyncPromptContext.execute` release the terminal when cancelled.** ACP
  says the agent MUST release every terminal it creates (`terminals.mdx`). `execute` released it
  when the command ended and when a step failed, but not when the prompt was cancelled
  (`session/cancel` grace period, `maxPromptDuration`, `$/cancel_request`) while it waited in
  `terminal/wait_for_exit`: the client kept the terminal. It now sends `terminal/release` exactly
  once on every path.

- **Annotated agents work as framework-proxied beans and with inherited handlers.**
  `AcpAgentSupport` read `@AcpAgent` and the handler methods from the bean's own class only. A
  proxy that Spring (CGLIB), Quarkus (ArC) or Micronaut (AOP) generates for the bean is an
  unannotated subclass, so `create(proxy)` failed with "Class must be annotated with @AcpAgent",
  and handlers declared on a superclass were never found (the client got `-32601`). Discovery
  now walks the class hierarchy: `@AcpAgent` may be on a superclass, handlers are collected
  from every class up to `Object` and invoked on the instance given (through the proxy, so its
  interceptors run), an annotated override is one handler, and bridge and synthetic methods are
  skipped. `agentInfo.name` defaults to the annotated class's simple name, not the proxy's.

- **An annotated agent advertises what its annotations declare.** Without an `@Initialize` method,
  `AcpAgentSupport` answered `initialize` with `InitializeResponse.ok()`: `loadSession: false`, no
  session, auth or provider capabilities, no `authMethods` and no `agentInfo`. An agent with
  `@LoadSession`, `@ListSessions`, `@ResumeSession`, `@CloseSession`, `@DeleteSession`, `@Logout`
  or `@Authenticate` told clients it supported none of them, and clients that honour capabilities
  never called those methods; `@AcpAgent(name, version)` was never sent. The response is now
  derived from the annotation model:
  - each handler annotation advertises the capability its method needs: `@LoadSession` →
    `loadSession`; `@ListSessions`, `@ResumeSession`, `@CloseSession`, `@DeleteSession` →
    `sessionCapabilities.list`/`resume`/`close`/`delete`; `@Logout` → `auth.logout`; the unstable
    `@ForkSession` → `sessionCapabilities.fork` and `@ListProviders`/`@SetProvider`/`@DisableProvider`
    → `providers`. The others need no capability (baseline methods, per-session modes and config
    options, extensions), and `HandlerAnnotationCoverageTest` fails if a new handler annotation has
    no declared mapping;
  - `@AcpAgent(name, version, title)` → `agentInfo`; a blank name sends the class's simple name, a
    blank version the jar manifest's `Implementation-Version`, else `"unknown"`;
  - new `@AcpAgent(authMethods = @AuthMethod(...))` → `authMethods`, agent or terminal methods.
    Terminal methods are advertised only to a client that announced
    `clientCapabilities.auth.terminal`. Declaring an agent method without an `@Authenticate`
    handler fails the build, as do duplicate ids and an `env` entry not written `NAME=value`;
  - new `@Prompt(image, audio, embeddedContext)` → `promptCapabilities` and
    `@AcpAgent(mcpHttp, mcpSse)` → `mcpCapabilities`, default false;
  - `protocolVersion` is the client's when the SDK speaks it, otherwise the latest it speaks.

  Interceptors see the derived `initialize` exactly as they see a declared `@Initialize` method
  (`preInvoke`, `postInvoke`, `onError`, `afterCompletion`).

  An `@Initialize` method is now optional. When present, its response is laid over the derived
  one: a capability is advertised when either side advertises it, so existing code returning
  `InitializeResponse.ok()` keeps every derived capability; returned `authMethods` follow the
  derived ones, replacing any with the same id; the returned `protocolVersion`, and the returned
  `agentInfo` and `_meta` when not null, win.

  **Migration:** an `@Initialize` that only returned `InitializeResponse.ok()` can be deleted. One
  that built capabilities by hand can drop what the annotations now derive. A capability a handler
  implies can no longer be withdrawn from `@Initialize`; remove the handler instead.
- **`session/close` cancels the session's ongoing work before it closes.** ACP v1 says the agent
  "**must** cancel any ongoing work related to the session (treat it as if `session/cancel` was
  called) and then free up any resources associated with the session" (schema
  `CloseSessionRequest`). The agent session passed `session/close` straight to its handler: a
  running prompt kept running and the `session/cancel` handler was never told. Now, when the agent
  has a `session/close` handler, the session first does what a `session/cancel` does: it tells the
  `session/cancel` handler (`cancelHandler`, `@Cancel`) and marks a running prompt cancelled, so
  it answers stop reason `cancelled` (by itself, or from the session after the cancel grace
  period). The close handler runs once that prompt has answered. An agent without a close handler
  still answers `session/close` with "Method not found" and cancels nothing.

- **A response result that lacks a required field fails the request.** Only inbound params were
  checked for the fields the ACP schema requires, so a peer's `{}` read as a `PromptResponse` whose
  `stopReason()` was null, or a `NewSessionResponse` whose `sessionId()` was null, against the
  records' `@NullMarked` contract. A result is now checked the same way, on both sides and down
  into nested records: the caller's `Mono` fails with an `AcpError` with code `-32603` (internal
  error), the message `The response to <method> lacks the required field <path>` (for example
  `modes.currentModeId`) and data `{"reason": "missing-required-field", "method": <method>,
  "field": <path>}`. JSON-RPC 2.0 defines no code for an invalid response. A null result for a
  response type whose fields are all optional still reads as `{}`.

  **A caller catches one type, `AcpError`, for every request that failed with an error code:** a
  peer's error response, or a response the SDK rejected itself. Besides a missing required field,
  a response without a result (data reason `missing-result`; it failed with an
  `AcpProtocolException`) and a result that cannot be read as the method's result type (reason
  `unreadable-result`; it failed with the mapper's `IllegalArgumentException`) now fail with an
  `AcpError` with code `-32603`. A rejected response's data is a map with `reason` and `method`;
  a peer's error carries the peer's data. `askChoice` answered with an option it did not offer
  fails the same way (reason `unoffered-option`). Timeouts and capability checks keep their own types.
  `AcpProtocolException` remains what a handler throws to answer with an error. **Migration:**
  catch `AcpError` instead of `AcpProtocolException` (or `IllegalArgumentException`) around a
  client or agent call.

- **A failed close is logged as the failure it is.** `AcpSyncClient.closeGracefully()` logged
  "Client didn't close within timeout of 10000 ms" for any failure, also one that happened at once;
  it now logs that only when the timeout passes, and otherwise `Client close failed: <cause>` with
  the cause (it still returns false). `AcpTransport.close()`'s default subscribed to
  `closeGracefully()` without an error consumer, so a failure reached Reactor's "Operator called
  default onErrorDropped" at ERROR; it is now logged at WARN by `AcpTransport`'s logger. The
  transports' and the client session's internal pipelines that subscribed without an error
  consumer now log their error (at WARN, or at DEBUG where it was already logged or reported), and
  a stdio client's standard-error handler that throws is logged instead of being dropped.

- **Every transport can be closed more than once, with `close()` and `closeGracefully()` in
  either order.** `WebSocketAcpClientTransport.closeGracefully()` sent a second WebSocket close
  frame on a second call, which failed with `IOException: Output closed`, so a second
  `AcpSyncClient.closeGracefully()` returned false and logged stack traces (try-with-resources after
  an explicit close, or a container that destroys a client bean more than once, did this). Only
  the first call now closes; later calls complete when it has, and a close frame is not sent once
  the connection's output is closed. `TransportCloseIdempotenceTest` closes every shipped transport
  (WebSocket client, Streamable HTTP client and its agent-side connection over HTTP and WebSocket,
  stdio client and agent, in-memory client and agent) twice in each order.

- **The stdio client's graceful close lets the agent exit by itself, and logs the stop once.**
  `StdioAcpClientTransport.closeGracefully()` sent SIGTERM straight away without closing the
  agent's standard input, so an agent's end-of-input handling (an SDK stdio agent answers what it
  received, flushes, and exits 0) never ran and every close ended in exit 143. It now closes the
  agent's standard input first and waits up to `StdioAcpClientTransport.END_OF_INPUT_WAIT_MILLIS`
  (2 seconds) for the agent to exit; only an agent still running then is sent SIGTERM, and killed
  five seconds later as before. Closing again, as `closeGracefully()` followed by try-with-resources
  `close()` does, no longer stops the process a second time or logs "ACP agent process stopped"
  twice.

- **`prompt()` returns only after the session-update consumers have handled the turn's updates.**
  On the client, notifications are delivered one at a time on their own drain, while a response
  completed its caller as soon as it arrived. A prompt's response follows its turn's updates, so a
  sync `prompt()` (or the async `Mono`) could return while the consumer was still handling the
  last updates, and a caller that read what it had collected missed some, silently. Now every
  response from the agent completes its caller only once every notification that arrived before it
  on the session has been handled: the response takes its place in the notification queue
  (`InboundOrder`, model checked with Lincheck in `InboundOrderLincheckTest`). Notification order,
  `closeGracefully()` waiting for the drain, and `close()` not waiting are unchanged; a response
  still held when the session closes is completed then. A slow consumer delays the response, which
  still counts against the request timeout. So that a consumer which sends a request of its own and
  waits for it cannot deadlock, a response is not held behind a handler that was already running
  when its request was sent. A consumer must not wait for the prompt in flight to complete. The
  agent side is unchanged: its notification handlers are not ordered, and `session/cancel` takes
  effect on the inbound thread before any later message is handled.

- **Closing the Streamable HTTP servlet is bounded, and an `initialize` in flight no longer
  outlives it.** `StreamableHttpAcpServlet.destroy()` waited up to 30 seconds (the initialize
  timeout) for its connections, and `closeGracefully()` had no bound at all, so an agent whose
  close did not finish held the container's shutdown. Both now finish within the new
  `StreamableHttpAcpAgentTransportOptions.shutdownTimeout` (default 5 seconds; see Changed): a
  connection whose agent has not closed by then is closed at once (`RemoteAcpConnection.close()`
  now also closes a connection whose graceful close has not finished). The listener
  (`StreamableHttpAcpAgentTransport.closeGracefully()`) bounds its WebSocket connections the same
  way. An `initialize` still being answered when the servlet closes is answered `503` at once,
  instead of holding its request until the agent answered or 30 seconds passed, and an answer that
  arrives after the close no longer registers a connection on the closed servlet (it used to stay
  open, with its agent, until the JVM exited). Closing never waits for a client: SSE responses are
  completed, not drained.
  The 30-second stall reported from Spring Boot is not `destroy()`: Boot shuts its server down
  gracefully by default, and every SSE stream a connected client holds is an in-flight request,
  so Boot waits its `timeout-per-shutdown-phase` (30 seconds) before servlets are destroyed. The
  servlet Javadoc now documents the remedy: call `closeGracefully()` before the server stops (a
  `SmartLifecycle` in the default phase).

- **The stdio agent transport's hold on `System.in` is documented.** Closing a
  `StdioAcpAgentTransport` built on `System.in` cannot end a read already in progress, because a
  read of `System.in` cannot be interrupted: its `acp-agent-inbound` thread stays blocked, holding
  `System.in`'s lock, until the next line or the end of standard input, then discards what it read
  and ends (now covered by a test). Under Maven Surefire, standard input is the fork's command
  stream, so such a reader, left by a test that builds the transport on `System.in`, took the
  fork's commands and stalled its exit until Surefire killed it 30 seconds after `System.exit(0)`.
  The class, its `System.in` constructors and `closeGracefully()` now say so, and that embedders and
  tests should pass explicit streams, use `acp-test`'s in-memory transport, or install an empty
  standard input before the tests run. The SDK's own tests never start a reader on `System.in`; the
  one that built the transport on it now passes explicit streams. The `AcpAgentSupport` Javadoc
  example no longer calls a `StdioAcpAgentTransport.create()` that does not exist.

- **An `@Prompt` method taking the async `PromptContext` failed every call.** `PromptContextResolver`
  accepted the parameter, but `AcpAgentSupport` supplied only the `SyncPromptContext`, so the prompt
  was answered with `-32603` "PromptContext is only available for @Prompt handlers". The parameter
  now receives the context the sync one wraps (`SyncPromptContext.async()`), so a `@Prompt` method
  can take either and return a `Mono<PromptResponse>` composed from the async calls.

- **Annotated handlers other than `@Prompt` could not take `NegotiatedCapabilities`.** Only the
  prompt handler was given them, so a `@NewSession` (or any other) method taking them failed with
  `-32603` "NegotiatedCapabilities not available in current context". Every handler, `@Initialize`
  and extension handlers included, now receives the capabilities negotiated on its own connection
  (the agent records them from the client's `initialize` request before the handler runs).

- **A handler that threw an `Error` left the peer waiting.** Reactor rethrows what it treats as
  JVM-fatal (`LinkageError`, `VirtualMachineError`) instead of signalling it, so a handler that
  threw, say, the `NoSuchMethodError` of code compiled against 0.18.0 calling a removed constructor
  unwound the handler thread, its request was never answered, and the peer waited out its request
  timeout. Now any `Throwable` escaping a request handler, sync or async, on the agent and the
  client, annotation-based handlers included, is answered with `-32603` (Internal error), message
  `Internal error in the <method> handler (<error type>)` with no stack trace, error message or
  payload; it is logged at ERROR (`InboundMessages`) with the throwable, and the connection keeps
  serving. A notification handler's `Error` is logged at ERROR and skipped. A
  `VirtualMachineError` (`OutOfMemoryError`, `StackOverflowError`) is not hidden: it is answered
  the same way as far as the JVM still can, and also handed to the uncaught-exception handler of
  the thread that ran the handler, where it would have gone had it escaped (JVM options such as
  `-XX:+ExitOnOutOfMemoryError` act when it is thrown and are unaffected). Not reachable: an async
  handler's `Mono` that throws a JVM-fatal error inside its own operators after the handler
  returned, which Reactor rethrows on the emitting thread. `HandlerFailures` (`util`) holds the
  guard.

- **`AcpAgentSupport.Builder` could not be built twice.** `build()` appended the default argument
  resolvers and return value handlers to the builder's own lists, so a second build duplicated
  them, a custom resolver or handler added after the first build came after the defaults and never
  ran, and agents already built shared, and saw changes to, the builder's lists. `build()` now
  composes the custom entries and then the defaults into a snapshot without changing the builder;
  each agent keeps the configuration it was built with.

- **Params of an application's own record type were rejected as missing their fields.** The
  required-field check on inbound params (`-32602`) walked every record, so a non-public record
  that the SDK could not read reported each of its fields missing, and an application record was
  held to rules only the schema defines. It now checks the schema's records (`AcpSchema`) only.

- **A stdio agent dropped the replies to requests that arrived just before its input ended.** When
  the client closed the agent's standard input, `StdioAcpAgentTransport` completed
  `awaitTermination()` at once and stopped writing, so an agent written as documented
  (`agent.start().then(agent.awaitTermination()).block()`) exited with replies still to come: a
  client that writes its requests and closes the pipe (a script, a test) got none, or some, of its
  answers. The end of standard input is now a half-close, not a cancellation: every request already
  received is handled and answered, the notifications its handler sends are written in order, then
  standard output is closed and `awaitTermination()` completes. A request the agent sends to the
  client, which can no longer answer, fails at once: one waiting when the input ends with a `-32603`
  error response, one sent after it with an `AcpConnectionException`. The drain is bounded by a drain
  timeout, `StdioAcpAgentTransport.DEFAULT_DRAIN_TIMEOUT` (60 seconds) or the one given to the new
  constructor `StdioAcpAgentTransport(AcpJsonMapper, InputStream, OutputStream, Duration)`; a request
  still unanswered then is answered with `-32800` (request cancelled) and the transport terminates
  without it. `closeGracefully()` now also completes `awaitTermination()`.
- **JSON-RPC request validation (JSON-RPC 2.0 sections 4 and 5.1), on both sides and every
  transport.** A request whose `method` is not a string was answered `-32601` (method not found);
  one whose `id` is not a string, number or null (an object, say), or whose `jsonrpc` is not exactly
  `"2.0"`, was executed. All three are now answered `-32600` Invalid Request, with the request's id
  when it can be read and `null` otherwise (section 5); `AcpSchema.deserializeJsonRpcMessage` refuses
  them, and `unreadableMessageResponse` answers them. Over Streamable HTTP a JSON object posted on a
  connection that is no valid request is answered on the connection stream with `202`, as the
  TypeScript server does; a body that is not a JSON object is still refused with `400`. Params the
  method cannot read are now `-32602` Invalid params, message `Invalid params`, with the reason as
  `data`, instead of `-32603`, and the handler is not called: a value of the wrong JSON type, and a
  required field (a schema record component without `@Nullable`, the schema's `required` list)
  that is missing, such as `session/new` without `cwd`. The new `AcpTransport.unmarshalParams`
  does this for both the agent's and the client's handlers. A notification whose handler fails
  (such params, say) is now logged at WARN and skipped; on the agent the failure used to end the
  session's inbound stream, so the connection stopped answering. The Streamable HTTP client now
  answers an SSE event that is no JSON-RPC message, in the scope of its stream, as the stdio and
  WebSocket transports do; it used to skip it, and the agent waited for an answer.
- **Behaviour change: the default JSON mappers no longer coerce scalars.** `JacksonAcpJsonMapper`
  and `Jackson3AcpJsonMapper` read `"cwd": 42` as the string `"42"`, and a string as a number or
  boolean; they now refuse a scalar of the wrong JSON type (`ALLOW_COERCION_OF_SCALARS` off, and no
  number or boolean to string), so such params are `-32602`.
- **A stray response without an id was logged at ERROR as a bug in this SDK.** A response with no
  id and no error answers no request this side sent; it is now logged at WARN.
- **A JSON-RPC error's wire `message` carried the code.** An `AcpProtocolException` thrown by a
  handler was answered with the exception's log form as the message, for example
  `"message": "[-32602] unknown directive: #nope"`; JSON-RPC 2.0 (section 5.1) keeps the number in
  `code` and a short description in `message`. The wire message is now the plain message
  (`"unknown directive: #nope"`). `AcpProtocolException.getMessage()` still prefixes the code, for
  logs; the new `getErrorMessage()` returns the plain message, and `JSONRPCError.from` uses it, so a
  received error passed on is unchanged.
- **Streamable HTTP routed `session/delete`, `session/close`, `session/list`,
  `session/set_config_option`, `session/fork` and `logout` by guesswork, and the server refused a
  session it did not know with 404.** The client had rules for only seven methods and inferred the
  rest from their params with a WARN per call; the server likewise. Both now route every
  client-to-agent method of ACP v1, stable and unstable, from a table: `authenticate`, `logout`,
  `session/new`, `session/list`, `providers/*`, `nes/start`, `mcp/message` and `$/cancel_request` on
  the connection; every method whose params require a `sessionId` (`session/prompt`, `cancel`,
  `set_mode`, `set_config_option`, `close`, `delete`, `fork`, `load`, `resume`, `nes/suggest`,
  `accept`, `reject`, `close`, `document/*`) session-scoped with `Acp-Session-Id`, as the transport
  RFD's Identity Model asks and the Rust and TypeScript SDKs do. Only methods outside v1, such as
  extension methods, are still routed by their params (logged at DEBUG). The server no longer
  answers a session-scoped POST naming a session the connection does not know with 404: the RFD's
  POST decision tree has none, so the request goes to the agent, which decides; a `session/delete`
  of a session that never existed succeeds, as `session-delete.mdx` asks. Its reply comes on that
  session's stream when it has one, else on the connection stream. `session/delete` and
  `session/fork` are accepted without `Acp-Session-Id`, as `session/load` was, and answered on the
  connection stream: the RFD names no session-scoped method and the Python client sends neither
  with the header.
  A session-scoped GET for a session the connection does not know still opens a bounded
  provisional stream: the RFD's GET tree says 404, but its own resume flow opens the session stream
  before `session/load`, and the TypeScript client does so for `session/load` and `session/delete`
  (it fails against a server that answers 404). The client now copes with a server that does answer
  404 (the Python server): it posts the request on the connection without `Acp-Session-Id`, and the
  reply comes on the connection stream.
- **A stdio client did not notice its agent process exiting.** `StdioAcpClientTransport` did not
  implement `awaitTermination()`, so when the agent exited or crashed, pending requests waited out the
  request timeout. It now completes when the transport is closed, and errors with an
  `AcpConnectionException` such as `ACP agent process exited with code 137 (signal 9)` once the
  agent's output has ended and the process has exited on its own; the session fails pending requests
  with it at once.
- **A peer's error answer to a message it could not read was logged as an SDK bug.** An error
  response with `"id": null` (what JSON-RPC 2.0 prescribes, and what this SDK and its peers send, for
  a message whose id could not be read) was logged at ERROR as "a bug in the request sender code".
  It is now logged at WARN as the peer's error report, with its code and message.
- **Inbound payloads no longer reach logs above DEBUG.** An unhandled notification was logged at
  WARN with its params: Claude's and Codex's agents send `_auth/status_update`, whose params carry
  the account's email address in login mode. `AcpClientSession` now logs the method only, as
  `AcpAgentSession` already did. The same rule now holds wherever an inbound message reached a log
  at INFO or above: an error response's `data` (`OutboundMessages`), a line from the agent that is
  not a JSON-RPC message and a message the stdio client could not queue (`StdioAcpClientTransport`),
  and a message of unknown type (both sessions). Each is logged at DEBUG instead. The exception
  `AcpSchema.deserializeJsonRpcMessage` throws for JSON that is no JSON-RPC message, which every
  transport logs, no longer quotes the text. A dropped outbound response is logged without its
  content too (`OutboundSinks`).
- **`closeGracefully()` could wait out the whole request timeout when a notification arrived
  as it closed.** Found by the real-agent smoke tier: Claude's ACP agent sends a
  `session_info_update` about 10 ms after a prompt answers, and closing the client right after the
  turn hung for the request timeout in 4 of 9 runs. A notification handler runs on the inbound
  thread inside the emission on the session's notification sink; the close's completion of that
  sink collided with it, was refused (`FAIL_NON_SERIALIZED`) and dropped, and the close then waited
  for a drain that never ended. Completion is now never lost: the close records that completion is
  wanted, and an emission it collided with completes the sink once it returns (model checked with
  Lincheck, `NotificationQueueLincheckTest`). A bounded busy-loop retry would not do: a handler
  that works synchronously holds the sink for as long as it runs. Notifications are still delivered
  in order through one drain, `closeGracefully()` still waits for it at most the request timeout,
  and `close()` still interrupts it.
- **Both stdio transports stopped reading on the first line that was not a JSON-RPC message.**
  `StdioAcpAgentTransport` and `StdioAcpClientTransport` ended their inbound stream on one malformed
  line, so the peer's later messages were never read; the client did so without telling anyone. Such
  a line is now logged, reported to the transport's exception handler, answered, and skipped, and
  reading goes on; a blank line is skipped silently. The answer is the one JSON-RPC 2.0 prescribes
  when the receiver cannot read a message, so cannot know whether it was a request: an error
  response with `"id": null` and code `-32700` (`Parse error`) for text that is not JSON, or
  `-32600` (`Invalid Request`) for JSON that is no JSON-RPC message. Both stdio sides answer, since
  each serves the requests of the other; the TypeScript SDK's stdio and WebSocket streams do the
  same. `StdioAcpClientTransport` now implements `setExceptionHandler` (it inherited the no-op
  default), and also reports read and write failures to it.
- **The WebSocket transports now answer a malformed message the same way.**
  `WebSocketAcpClientTransport` already skipped and reported one, and now also answers it.
  `StreamableHttpAcpAgentTransport`'s WebSocket upgrade closed the whole connection (code 1002) on
  one; it now answers, reports and skips it, and the connection stays open. A Streamable HTTP POST
  with a malformed body is still refused with 400, the HTTP answer, and a malformed SSE event is
  skipped unanswered (an SSE stream has no reply channel) but is now reported to the client
  transport's exception handler. `AcpSchema.unreadableMessageResponse(jsonMapper, text)` builds the
  answer, and `JSONRPCResponse` now writes a null id as `"id": null` (it omitted it), as JSON-RPC 2.0
  requires of a response.
- **The transport errors of a Streamable HTTP or WebSocket connection were only logged.** The agent
  runtime installs no exception handler on the transport `RemoteAcpConnection` gives it, and the
  listener offered no way to install one, so a host never saw a connection's transport errors.
  `StreamableHttpAcpAgentTransport.setExceptionHandler` and `StreamableHttpAcpServlet.setExceptionHandler`
  now receive them for every connection, HTTP/SSE and WebSocket alike, as
  `StdioAcpAgentTransport.setExceptionHandler` does for stdio; the default still logs them, and an
  agent factory may still install its own on the transport it is given. `RemoteAcpConnection` takes
  the handler as a new constructor argument.
- **A request that timed out or was cancelled stayed registered until the session closed.** Both
  session sides kept the entry for every request still awaiting a response, and removed it only when
  the response arrived or the session ended. A request that hit the request timeout, or whose
  subscriber cancelled, kept its entry, so a long-lived session grew by one entry per such request,
  and a late response was silently swallowed rather than logged as unexpected. The entry is now
  removed when the request times out or is cancelled (when its sink is disposed, and only if the
  entry is still that request's). Found by the PIT mutation-testing pilot and, independently, by
  model checking with Lincheck (`PendingResponsesLincheckTest`).
- **Streamable HTTP: a JSON-RPC message with `"id": null` broke the agent transport.** A client
  request posted with `"id": null` (legal JSON-RPC) was answered with `"id": null`, and routing that
  answer looked the id up in a map that rejects null keys; the `NullPointerException` closed the whole
  connection. A client error response with `"id": null` (JSON-RPC's answer to a request it could not
  parse) failed the POST with 500. The answer now goes to the connection stream, and the response is
  accepted with 202.
- **`StreamableHttpAcpClientTransport`: a response with `"id": null` failed the stream reader.** An
  agent's error response with `"id": null` (JSON-RPC's answer to a request it could not parse) was
  looked up in a map that rejects null keys, and the `NullPointerException` replaced delivery; a
  client response with `"id": null` failed the same way on its way out. Both are now delivered.
- **`AgentParameters.Builder.arg` after `args(String...)` threw `UnsupportedOperationException`**:
  the varargs form kept the fixed-size `Arrays.asList` view. It now copies the arguments.
- **`StreamableHttpAcpAgentTransport`'s WebSocket upgrade closed the connection on any inbound text
  message over 64 KB** (Jetty's default; close code 1009), so a prompt carrying a file of that size
  ended the session. The single-client `WebSocketAcpAgentTransport` accepted 4 MB. The upgrade now
  accepts messages up to `StreamableHttpAcpAgentTransportOptions.maxPostBodyBytes` (16 MB by
  default), the same inbound limit as a Streamable HTTP POST body.
- **Streamable HTTP: an event written as the client dropped its SSE stream could be lost.** When a
  client closed a session stream (an HTTP/2 reset) and the agent emitted an event before the server
  had detached that stream, Jetty accepted the write and its flush, and reported the failure only on
  the next write; the event was counted as sent and the reconnecting client never received it (a
  prompt's `session/update` went missing while its result arrived). A subscriber now keeps each event
  it writes until the write has completed without error, checking a write that completed at once
  with an empty write, and returns unconfirmed events to the front of the stream's mailbox when it
  goes away, so the next GET receives them in order. An event whose write was still pending when its
  stream was replaced may now arrive twice rather than not at all. Bytes the server did put on the
  wire before the client's reset arrived can still be lost: closing that needs SSE event ids and
  `Last-Event-ID`, which the transport RFD defers to v2.
- **A response with `"result": null` failed the request** ("carried no result") even where the
  result is an empty object, a regression in this release: the Python SDK answers
  `fs/write_text_file` with `"result": null` when its handler returns `None`, so a Java agent could
  not write files through a Python client. As in the Rust SDK (`default_on_null`, schema 1.9.1), a
  response type whose every field is optional now reads a null or missing result as `{}`; these types
  implement the new marker `AcpSchema.DefaultOnNull` (`AuthenticateResponse`, `LogoutResponse`,
  `LoadSessionResponse`, `ResumeSessionResponse`, `CloseSessionResponse`, `DeleteSessionResponse`,
  `SetSessionModeResponse`, `WriteTextFileResponse`,
  `ReleaseTerminalResponse`, `KillTerminalCommandResponse`, `WaitForTerminalExitResponse`,
  `SetProviderResponse`, `DisableProviderResponse`). A response type with a required field still
  fails clearly. A null result of an extension (`_`-prefixed) method completes the request's `Mono`
  empty, and a message with an `id` but no `method`, `result` or `error` is read as a response
  without a result instead of being rejected.
- An interceptor's `afterCompletion` ran twice when a later interceptor vetoed the call or threw from
  `preInvoke`: once inside `InterceptorChain.applyPreInvoke` and again from the invocation's cleanup.
  `applyPreInvoke` no longer runs cleanup, and `triggerAfterCompletion` runs at most once per chain,
  so `afterCompletion` runs exactly once per invocation on every path.

Found by the NullAway adoption; each has a test.

- A request or notification that omits `params` (legal JSON-RPC) reached its handler as `null`; it now
  reads as an empty object, as if the peer had sent `{}`.
- A request whose handler produced no result got no JSON-RPC response at all, so the peer waited for
  its timeout: a core request handler that completed empty, and in `acp-agent-support` a `void` or
  null-returning handler method, or one vetoed by an interceptor. Such requests are now answered with
  an `INTERNAL_ERROR` saying the handler produced no response.
- A handler exception without a message produced a JSON-RPC error without the required `message`; the
  exception's type name is sent instead.
- A success response without a `result` failed the request with an opaque `NullPointerException`; the
  error now says the response carried no result.
- `PromptContext.askChoice` failed the prompt with a `NullPointerException` when the client cancelled.
- `PromptContext.execute` failed the prompt with a `NullPointerException` when the command was killed
  by a signal (no exit code).
- `AcpSchema.deserializeJsonRpcMessage` threw `NullPointerException` for the JSON `null` literal
  instead of the documented `IllegalArgumentException`.

Found by enabling Error Prone's bug checks; each has a test.

- `StdioAcpClientTransport` read the agent's stdout and stderr in the platform default charset
  while writing to it in UTF-8. On Java 17 that charset follows the platform (Cp1252 on Windows,
  US-ASCII under a POSIX locale), so non-ASCII text from the agent arrived garbled. Both are now
  read as UTF-8.
- The Streamable HTTP client rejected an initialize response whose `Content-Type` was upper case
  when the JVM's default locale was Turkish (`"APPLICATION/JSON".toLowerCase()` is
  `"applıcatıon/json"` there). The client's and the servlet's media-type checks, and the `os.name`
  check in `AgentParameters`, now lower-case with `Locale.ROOT`.
- In `acp-agent-support`, a handler method whose result was not the ACP method's response type
  failed with an opaque `ClassCastException` message; the `INTERNAL_ERROR` now names the method and
  both types.

Found by model checking with Lincheck; each has a Lincheck test as its regression.

- **`AcpAgentSession.hasActivePrompt()` and `getActivePromptSessionIds()` could report no active
  prompt while one was active**, when a prompt of another session started or ended at the same
  moment: they asked the `ConcurrentHashMap` for its size, whose counters concurrent updates change
  after the entries themselves. They now look at the entries.
- When the transport terminated while the session was closing, each request still waiting for a
  response was failed twice (Reactor dropped the second failure), and a request registered during
  that dismissal could be cleared from the table without being failed, leaving its caller to wait
  for the timeout. Each request is now removed from the table before it is failed, so it is failed
  exactly once.
- **Streamable HTTP: a session stream first used after the connection closed stayed open.** A
  session GET that raced the connection's close (a `DELETE`, or the agent ending the connection)
  held its response open until the client gave up, and agent messages for a session after close
  queued in a new stream nobody would read. Such a stream is now closed, so the GET completes at
  once, as on the connection stream. The connection's session table now makes each check and the
  change it decides one step.

Found by measuring coverage with JaCoCo; each has a test.

- **Annotated agents registered by class got a new instance for every request.**
  `AcpAgentSupport.create(MyAgent.class)` and `builder().agent(MyAgent.class, factory)` created the
  agent anew on each handler call, so state kept in its fields (sessions stored by `@NewSession` and
  read by `@Prompt`, as in the module README's complete example) was lost between requests. The
  agent is now created once, when it is registered, and that instance serves every request; a class
  that cannot be instantiated fails at registration with an `IllegalArgumentException` rather than on
  the first request. The module README's factory example, which called a
  `create(Class, Supplier)` that does not exist, is corrected.

### Build

- **The Javadoc build knows the `@apiNote`, `@implSpec` and `@implNote` tags.** They were not
  registered with the javadoc plugin, so their text was silently left out of the generated pages
  (and doclint reports them as unknown tags). The parent POM now registers them, with the JDK's
  headings.

- **The JaCoCo coverage gate skips with the tests** (`-DskipTests`). It read whatever execution data
  an earlier run had left in `target/`, so `./mvnw -DskipTests install` after a `-Dtest` run (a
  partial `jacoco.exec`) failed the gate; this broke the cross-SDK suite's SDK install.
- Model checking with Lincheck (`org.jetbrains.lincheck:lincheck` 3.7, test scope) for the SDK's
  concurrent state: the single-turn prompt rule and its release before the response is published
  (#14), the requests waiting for a response, emission on a transport's outbound sink, the
  Streamable HTTP SSE mailbox (no event lost or reordered across reconnects, client resets and
  pending writes) and the connection's session table. Each test checks every interleaving of a
  bounded, seeded set of scenarios against a sequential specification and invariants, and was
  shown to fail, with a minimal interleaving, against the bug it guards. They run in the normal
  build (about a minute in total); the `lincheck` CI job runs them with ten times the scenarios.
  The single-turn rule and the requests table moved into their own classes (`ActivePrompts`,
  `PendingResponses`) so that they can be checked without a transport.

- Architecture rules (ArchUnit) guard the package structure: acp-core's layers (util and json under the
  protocol, protocol under capabilities, capabilities under client and agent, which never depend on each
  other), no package cycles, no Jackson databind in acp-core, and no Jetty types on the path of
  `StreamableHttpAcpServlet`, which must stay mountable in any Servlet 6 container.
- The architecture rules cover every module. Each module's rules import only that module's main
  classes and check that the import is non-empty (`archRule.failOnEmptyShould=true`), and every module
  is free of package cycles at any depth. acp-annotations depends on nothing in the SDK; the JSON
  modules use only acp-core's JSON SPI and the schema; acp-agent-support layers `AcpAgentSupport` over
  independent resolver, handler and interceptor packages over the invocation model, and uses only
  acp-core's public agent API (no session implementations, client code, concrete transports or JSON
  implementation); acp-test uses only public SDK types and nothing from the annotation layer; and the
  transports, in acp-core and acp-streamable-http-jetty, see only the protocol (the `spec` transport
  interfaces and schema) plus, on the agent side, the per-connection seam (`AcpAgentFactory`,
  `AcpAsyncAgent`), never the session implementations or other client and agent runtime classes. The
  session (`spec`) and transport packages also have no dependency cycles between classes.
- Null safety is enforced, not just declared: every package is `@NullMarked` (JSpecify 1.0.0, a
  compile dependency of each module; optional in `acp-annotations`, which keeps no transitive
  dependencies), and NullAway 0.13.8 on Error Prone 2.50.0 checks main sources at ERROR. Error Prone
  needs JDK 21, so the check runs in a `nullaway` profile activated on JDK 21+ and in a new JDK 21 CI
  job; the JDK 17 build and release are unchanged. Compilation uses `--release 17`, so a JDK 21 build
  still compiles against the Java 17 API. `.mvn/jvm.config` opens the javac internals Error Prone needs
  (harmless on JDK 17).
- Error Prone's own bug checks run beside NullAway, at ERROR, in the JDK 21 build. The profile is
  renamed `errorprone` (from `nullaway`), and so is its CI job. Every check Error Prone enables by
  default runs, and `failOnWarning` makes its warnings fail the build too (as it does javac's own
  default warnings, such as use of an API deprecated for removal). One check is disabled as style,
  `StatementSwitchToExpressionSwitch`. Two more are enabled: `SystemOut`, since anything printed to
  standard out corrupts a stdio agent's JSON-RPC stream, and `CheckReturnValue` over
  `reactor.core.publisher`, so a Mono or Flux operator whose result is dropped (and never runs)
  fails the build; `config/errorprone/reactor-ignorable-results.txt` lists the results that may be
  dropped, with reasons. Still main sources only, still JDK 21+ only.
- A scheduled dependency vulnerability scan (`dependency-scan.yml`) checks the resolved runtime
  dependency tree of every module against OSV weekly, on demand, and on each push to `main` that
  changes a `pom.xml`, so a CVE published after a release no longer waits for someone to look. Maven
  resolves the tree (a CycloneDX BOM, test scope excluded) and osv-scanner 2.6.0 checks it; no API key
  or secret is involved. The job fails on HIGH or CRITICAL (CVSS 7.0 or more) or an unscored finding,
  lists lower findings without failing, and uploads SARIF to code scanning. An unfixable finding can be
  accepted in `osv-scanner.toml` with a reason and an `ignoreUntil` date, after which it fails again.
  Run it locally with `.github/scripts/osv-scan.sh`.
- A size, complexity and duplication gate fails `verify`: maven-pmd-plugin 3.28.0 (PMD 7.28.0) runs
  `pmd:check` and `pmd:cpd-check` on main sources with the ruleset in `config/pmd/ruleset.xml`, which
  records each threshold and the measurement behind it. Per method: cognitive complexity 15,
  cyclomatic complexity 10, 30 statements (NCSS), and 30 statements in one lambda body (a rule of the
  ruleset's own, since NCSS does not look inside lambdas); per class: 300 statements and cyclomatic
  complexity 80; 7 parameters; CPD at 100 tokens. There is no baseline: the code was refactored to
  pass. The one exemption is structural, the class-size limit for a wire record container
  (`AcpSchema`). Agent handlers are registered as data, so `DefaultAcpAsyncAgent.start()` (150 lines)
  is a loop; `StreamableHttpAcpClientTransport` (1,045 lines) is a facade over package-private
  classes for HTTP exchanges, routes, SSE streams and inbound delivery; the two sessions share their
  request plumbing. The class-cycle rule in acp-core's `ArchitectureTest` now covers every package
  except `json` (whose default-mapper lookup is a cycle by design), with the `AcpAgent` <->
  `DefaultAcpAsyncAgent` cycle gone.
- A bug-pattern gate fails `verify`: SpotBugs 4.10.4 (spotbugs-maven-plugin 4.9.8.5; the 4.10
  plugin needs Maven 3.8.9 and the wrapper is 3.8.6) runs `spotbugs:check` on main sources in every
  module, on JDK 17 and JDK 21 alike, at effort Max, and fails on any finding of rank 9 or less
  (SpotBugs' "scariest" and "scary" bands). Rank, not confidence alone, is the gate: it weighs the
  pattern's severity with its confidence, so the long tail of rank 10 to 20 advice (exposed record
  arrays and collections, inner classes that could be static, serialization hygiene) does not fail
  the build. find-sec-bugs was evaluated and not added: it raised nothing at rank 9 or less, and its
  findings below that are the library's purpose (starting the configured agent process, writing
  JSON-RPC to a servlet response). The first run found three: two false positives, excluded one by
  one with their reasons in `config/spotbugs/spotbugs-exclude.xml`, and the Streamable HTTP SSE
  stream locking on its own monitor, which any code holding the stream could also take; it now
  locks a private object.
- The SpotBugs gate also fails on concurrency bugs below rank 9. SpotBugs ranks most
  multithreaded-correctness patterns 14 to 17 (inconsistent synchronization is 17), so a rank gate
  alone never sees them. The analysis now reports up to rank 20, and
  `config/spotbugs/spotbugs-include.xml` selects what fails the build: every pattern of rank 9 or
  less, plus a listed set of concurrency patterns at any rank (inconsistent synchronization,
  wait/notify and explicit-lock misuse, broken double-checked locking, `volatile` increments,
  locking on the wrong object, mutable servlet fields), each group with its reason, and the
  concurrency patterns left out with theirs. The code base had no such finding. One analysis serves
  both: a second `check` execution with its own filter does not work, since `check` analyses with
  the plugin-level configuration and the second execution would pass on a report never written.

- A coverage gate fails `verify`: JaCoCo's `check` requires each module's line and branch
  coverage to stay at or above floors declared in its `pom.xml` (`jacoco.minimum.line`,
  `jacoco.minimum.branch`). Each floor is the coverage measured from a clean `verify`, less a margin
  of 2 points, rounded down to a whole percent: line / branch floors are acp-core 82 / 70,
  acp-agent-support 85 / 76, acp-streamable-http-jetty 86 / 76, acp-json-jackson2 98 / 60,
  acp-json-jackson3 94 / 60 and acp-test 76 / 38; acp-annotations has no executable code. Each
  module counts only its own classes, exercised by its own tests (and, in acp-core, its integration
  tests); the JSON modules run acp-core's contract suite but count only their mapper classes. Nothing
  is excluded, `AcpSchema`'s records included: they are covered like the rest (80 % of their lines).
  The parent's default floor is full coverage, so a new module fails until it declares measured
  floors. The per-module HTML report, now written in `verify` so it shows what the gate checks, is
  at `<module>/target/site/jacoco/index.html`. The measurement found code no test in its own module
  reached, now tested: `RemoteAcpConnection`, the per-connection core of the remote agent
  transports (0 %, exercised only through acp-streamable-http-jetty); the WebSocket client
  transport's listener (fragmented and malformed frames, server close, socket errors, failed sends);
  the stdio client transport's reader and writer, which only a child JVM had run; and in
  acp-agent-support the fork, session-config and disable-provider handlers, `Mono` return values,
  and the errors a client gets when a handler cannot be invoked or answered (which found the bug
  above). acp-core moved from 77 % to 84 % of lines, acp-agent-support from 70 % to 88 %.

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
