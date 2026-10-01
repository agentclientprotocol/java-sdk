# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security

- Jackson 2.22.2 → **2.22.3** and Jackson 3.1.5 → **3.1.7**, clearing CVE-2026-89407, CVE-2026-89425,
  CVE-2026-91776, CVE-2026-91777 (both lines) and CVE-2026-19032, CVE-2026-68497, CVE-2026-83557 (Jackson 3).
  0.18.0 shipped with the affected versions; applications can override the versions now.

### Added

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
  timers run on the SDK's shared timeout timer, with no new threads. A handler that keeps running
  after its subscription is cancelled (a blocking sync handler is not interrupted) and sends more
  updates sends them after the answer. `AcpErrorCodes.REQUEST_CANCELLED` (`-32800`) is new.

### Changed

- **Behaviour change: `session/cancel` no longer ends the prompt turn; the cancelled prompt's
  response does.** The agent session used to free the session for a new prompt as soon as the
  cancel notification arrived, so a client could start a second prompt while the cancelled one's
  handler was still running. ACP v1 (prompt turn, Cancellation) says the agent may still send
  `session/update`s after the cancel and must then answer the original `session/prompt` with stop
  reason `cancelled`, and "once a prompt turn completes, the Client may send another
  `session/prompt`". A prompt sent between the cancel and that answer is now rejected with
  `-32000` (`CONCURRENT_PROMPT`), like any prompt during an active turn. The turn ends when the
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

### Removed

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

### Fixed

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
