# acp-micronaut

Micronaut 4 integration for the ACP Java SDK. It serves your `@AcpAgent` bean over stdio,
Streamable HTTP or WebSocket, and it builds an ACP client from configuration. It needs Java 17+.

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-micronaut</artifactId>
    <version>${acp.version}</version>
</dependency>
<!-- Only for acp.agent.transport.type=http (Streamable HTTP and WebSocket) -->
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-streamable-http-jetty</artifactId>
    <version>${acp.version}</version>
</dependency>
```

Micronaut's annotation processor (`micronaut-inject-java`) must be on your compiler's annotation
processor path, as in any Micronaut application.

## An agent

Annotate the agent class `@AcpAgent`. With `acp-micronaut-processor` on the annotation processor
path, beside `micronaut-inject-java`, that alone makes it a singleton bean; without it, add
`@Singleton` (any bean-defining annotation works). It is an ordinary bean, so other beans are
injected into it. An agent class compiled in another module or jar needs `@Import` (see
[An agent compiled elsewhere](#in-process-the-agent-and-the-applications-own-client)).

```xml
<annotationProcessorPaths>
    <path>
        <groupId>io.micronaut</groupId>
        <artifactId>micronaut-inject-java</artifactId>
        <version>${micronaut.core.version}</version>
    </path>
    <path>
        <groupId>com.agentclientprotocol</groupId>
        <artifactId>acp-micronaut-processor</artifactId>
        <version>${acp.version}</version>
    </path>
</annotationProcessorPaths>
```

```java
@AcpAgent(name = "echo", version = "1.0.0")
public class EchoAgent {

    private final Greeter greeter;

    public EchoAgent(Greeter greeter) {
        this.greeter = greeter;
    }

    @Prompt
    public AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
        context.sendMessage(greeter.prefix() + request.text());
        return AcpSchema.PromptResponse.endTurn();
    }
}

public final class Application {
    public static void main(String[] args) {
        // stdout carries the protocol over stdio: no banner, and log to stderr (logback.xml)
        Micronaut.build(args).mainClass(Application.class).banner(false).start();
    }
}
```

- **Finding the bean.** `AcpAgentRuntime` reads the `@AcpAgent` bean from the bean definitions
  the compiler wrote, without scanning the classpath. Exactly one such bean is allowed. With more
  than one, startup fails and names them all. An application without one, such as a client, starts
  no agent.
- **Handlers.** The handler methods, their parameters and return types are those of
  `acp-agent-support`. The `initialize` answer is derived from the class's annotations
  (`agentInfo` from `@AcpAgent`, a capability for each declared handler), also when Micronaut
  AOP advice makes the bean a generated subclass, which is then invoked so its advice runs. One bean instance serves every session, and over HTTP every connection, so
  keep per-session state keyed by session id. Handlers may also return a `Mono`, a single-value
  `Flux` or any Reactive Streams `Publisher` (its first element is the response), or a
  `CompletionStage`. acp-micronaut does not bring `micronaut-core-reactive`, so Micronaut's
  `Publishers` helper is on the classpath only if the application adds that dependency.
- **Extension beans.** Beans of type `AcpInterceptor`, `ArgumentResolver` and
  `ReturnValueHandler` are added to the agent in bean order.
- **Lifecycle.** The agent starts with the application context (`StartupEvent`) and closes
  gracefully with it.
  - **Over stdio**, the application stays up while the transport runs. When the client closes the
    agent's input and every answer has been written, the context closes and the process exits 0.
  - **Over HTTP**, the listener runs until the application stops.
  - **In process**, see below.
  - **Closing.** After the agent has stopped, an `AutoCloseable` agent bean is closed.
  - **On SIGTERM**, the agent closes gracefully. Without an embedded server, Micronaut registers no
    shutdown hook of its own, so the runtime registers one.
  - **Graceful shutdown.** With `micronaut.lifecycle.graceful-shutdown.enabled=true`, the HTTP
    listener drains through Micronaut's graceful shutdown.
- **Over stdio, standard output is the protocol.** Turn off the banner and send every log line to
  standard error. Logback's console appender writes to standard output unless you set
  `<target>System.err</target>`.

### Agent settings (`acp.agent.*`)

| Property | Default | |
|---|---|---|
| `acp.agent.enabled` | `true` | serve the `@AcpAgent` bean |
| `acp.agent.request-timeout` | `60s` | agent-to-client requests |
| `acp.agent.cancel-grace-period` | `60s` | after `session/cancel`, the SDK answers `cancelled` itself; `0s` for never |
| `acp.agent.max-prompt-duration` | `0s` (none) | a longer prompt is answered `-32800` |
| `acp.agent.shutdown-on-transport-end` | `true` | close the context when the stdio input ends |
| `acp.agent.shutdown-timeout` | `10s` | how long closing waits for a stdio agent's graceful close, then closes it at once |
| `acp.agent.transport.type` | `stdio` | `stdio`, `http`, `websocket` (the same listener as `http`) or `in-process`, in any case |
| `acp.agent.transport.http.listener.host` | loopback | the address the listener binds; unset binds `127.0.0.1` and `::1` only, `0.0.0.0` every interface (an explicit opt-in: the listener has no authentication) |
| `acp.agent.transport.http.allowed-origins` | none | browser origins accepted besides `localhost`, `127.0.0.1` and `[::1]`; any other `Origin` is answered 403, over HTTP and on the WebSocket handshake; `*` for any |
| `acp.agent.transport.http.listener.port` | `8080` | the listener's own port; `0` for an ephemeral one (`AcpAgentRuntime.port()`) |
| `acp.agent.transport.http.listener.max-concurrent-streams-per-connection` | SDK (1024) | HTTP/2 streams one client connection may hold open |
| `acp.agent.transport.http.path` | `/acp` | |
| `acp.agent.transport.http.max-post-body-size` | SDK (16MB) | e.g. `4MB` |
| `acp.agent.transport.http.keep-alive-interval`, `mailbox-capacity`, `max-pending-sse-events`, `max-web-socket-pending-frames`, `max-provisional-sessions`, `shutdown-timeout`, `web-socket-idle-timeout` (30m: an idle WebSocket is closed with 1001), `initialize-timeout` (30s: a WebSocket without `initialize` is closed with 1008) | SDK | `StreamableHttpAcpAgentTransportOptions`; closing waits for the listener at most `shutdown-timeout` plus 5 seconds |

An application bean of type `AcpAgentTransport` replaces stdio. `shutdown-on-transport-end`
applies to the stdio transport only: the end of any other never closes the context.

### In process: the agent and the application's own client

An application that drives its own agent serves it in process with one property:

```properties
acp.agent.transport.type=in-process
```

The runtime serves the `@AcpAgent` bean on one side of an `InMemoryTransportPair` and connects the
application's `AcpSyncClient` and `AcpAsyncClient` to the other, with the `acp.client.*` timeouts
and capabilities, and initializes the client once the agent has started. Inject the client and
open sessions. The application's handler beans receive the agent's updates and answer its
permission requests:

```java
@Singleton
class Chat implements AcpSessionUpdateHandler {         // every bean receives every update
    public void onSessionUpdate(AcpSchema.SessionNotification notification) { ... }
}

@Singleton
class Approvals implements AcpPermissionHandler {       // at most one; without it, requests are refused
    public AcpSchema.RequestPermissionResponse onPermissionRequest(AcpSchema.RequestPermissionRequest request) { ... }
}
```

Both may block. Closing the context closes the client, stops the agent and closes an
`AutoCloseable` agent bean; the end of the in-process connection never closes the context.
Setting an `acp.client.transport.*` property as well fails the startup.

**An agent compiled elsewhere.** Micronaut writes bean definitions only for the sources it
compiles, so the processor maps `@AcpAgent` only there. When the agent class comes from a module
built without Micronaut's processors, or from a library jar, import its package on one of the
application's own classes; with `acp-micronaut-processor` on the application's processor path, the
imported `@AcpAgent` class becomes a singleton bean as well:

```java
@Import(packages = "com.example.claims.agent")   // io.micronaut.context.annotation.Import
public class Application { ... }
```

**HTTP runs on its own port.** Streamable HTTP and WebSocket are served by the SDK's Jetty
listener (`StreamableHttpAcpAgentTransport`). It runs next to Micronaut's HTTP server if you have
one, not inside it. It serves HTTP/1.1, cleartext HTTP/2 and WebSocket upgrades on one path.

**Micronaut's security does not apply to it.** Because the listener is the SDK's own server,
Micronaut's filters, `micronaut-security` rules, CORS, TLS and metrics never see its requests. It
binds the loopback interface by default, so only programs on the same machine can connect, and it
refuses browser requests from foreign origins. Setting `acp.agent.transport.http.listener.host=0.0.0.0`
exposes an endpoint without authentication to the network: put a proxy that authenticates in front
of it. This is an interim arrangement; serving ACP on Micronaut's own server, where its security
applies, is planned for a later release.

## A client

A client is configured when a transport is named: `acp.client.transport.type`, `.stdio.command`,
`.websocket.uri` or `.http.uri` is set, as in Spring Boot and Quarkus. It has three beans: the transport,
an `AcpAsyncClient`, and an `AcpSyncClient` facade over that same client, which is one session on
one connection. Creating the client bean connects its transport (for stdio, it starts the agent
process); `initialize()` then performs the ACP handshake. It closes gracefully, once, with the
context. Customize it before it is built with `AcpClientCustomizer` beans
(`com.agentclientprotocol.sdk.integration.AcpClientCustomizer`, the same type in every framework
integration), applied in `@Order`:

```java
@Singleton
class Updates implements AcpClientCustomizer {
    public void customize(AcpClient.AsyncSpec spec) {
        spec.sessionUpdateHandler(notification -> { /* ... */ return Mono.empty(); });
    }
}
```

Without a session-update handler of your own, session updates are logged at DEBUG, content
included. DEBUG and TRACE on `com.agentclientprotocol.sdk` log message payloads in full; do not
enable them where logs are shipped. A stdio agent's standard error is logged at INFO, unfiltered,
on `com.agentclientprotocol.sdk.client.transport.agent-stderr`, and can carry sensitive data: set
that logger to `WARN` in `logback.xml` to silence it.

| Property | Default | |
|---|---|---|
| `acp.client.request-timeout` | `60s` | |
| `acp.client.prompt-timeout` | none | a longer prompt turn fails and is cancelled |
| `acp.client.transport.type` | inferred | `stdio`, `websocket` or `http`, in any case. When it is unset, the transport is the only one whose command or URI is set; with several set, it is required |
| `acp.client.transport.stdio.command`, `.args`, `.env.*` | | the agent process; `env` keys keep their case |
| `acp.client.transport.websocket.uri`, `.connect-timeout` | `10s` | `ws://host:port/acp` |
| `acp.client.transport.http.uri` | | `http://host:port/acp` |
| `acp.client.capabilities.read-text-file`, `write-text-file`, `terminal`, `elicitation-form`, `elicitation-url`, `boolean-config-options` | `false` | advertise one only together with its handler, registered in a customizer |

## Dependencies

The Micronaut modules import the Micronaut 4.10 platform BOM. The SDK's Reactor, Jackson, Jetty
and JUnit versions take precedence over it. The JSON wire format is the SDK's own Jackson mapper,
not Micronaut Serialization.

## Sample

`acp-micronaut-sample` is a runnable agent built on this module. Its tests start it as a process
over stdio and HTTP. In `integration-testing/`, the `micronaut-typescript-*` scenarios run it
against the TypeScript SDK's client.
