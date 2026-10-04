# ACP Spring Boot

Spring Boot 4.1 autoconfiguration for the ACP Java SDK. It serves your `@AcpAgent` bean over stdio,
Streamable HTTP or WebSocket, and it builds an ACP client from `spring.acp.client.*`. Two modules:

- `acp-spring-boot-starter`, the one to depend on. It brings this module, `acp-agent-support` and
  `spring-boot-starter`.
- `acp-spring-boot-autoconfigure`, this module: the autoconfiguration classes and the
  `spring.acp.*` properties, in package `com.agentclientprotocol.sdk.spring.boot.autoconfigure`
  (`.agent`, `.client`).

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-spring-boot-starter</artifactId>
    <version>${acp.version}</version>
</dependency>
<!-- Only for an agent served over HTTP: spring.acp.agent.transport.type=http or websocket -->
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-streamable-http-jetty</artifactId>
    <version>${acp.version}</version>
</dependency>
```

The starter has the SDK's version. Do not pin the other SDK modules to a version of their own: the
starter brings the matching ones. The client's stdio, WebSocket and Streamable HTTP transports are
in `acp-core`, so a client needs nothing more. Only an agent served over HTTP needs
`acp-streamable-http-jetty`. The SDK's Jackson 3 module is on the classpath, and the SDK chooses it
over Jackson 2 (see the root [README](../README.md#installation)).

## An agent

Annotate one bean `@AcpAgent`. Its handler methods, their parameters and their return types are
those of [`acp-agent-support`](../acp-agent-support/README.md).

```java
@Component
@AcpAgent(name = "echo", version = "1.0.0")
public class EchoAgent {

    @Prompt
    public PromptResponse prompt(PromptRequest request, SyncPromptContext context) {
        context.sendMessage("Echo: " + request.text());
        return PromptResponse.endTurn();
    }
}

@SpringBootApplication
public class AgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
```

Over stdio (the default), standard output carries the protocol, so nothing else may write to it.
The transport's threads are daemon threads, so keep the application alive:

```properties
spring.main.keep-alive=true
spring.main.banner-mode=off
# Spring Boot's console logging writes to standard output: turn it off, log to a file,
# or configure a logback-spring.xml whose console appender has <target>System.err</target>
logging.console.enabled=false
logging.file.name=agent.log
```

When the client closes the agent's standard input and every answer has been written, the agent
closes the application context, and the process exits 0
(`spring.acp.agent.shutdown-on-transport-end`).

- **Finding the bean.** The autoconfiguration finds the bean by its `@AcpAgent` annotation. Make
  the class a bean (`@Component`, or a `@Bean` method). The handler methods are read from the
  bean's own class, so a bean proxied by Spring AOP (CGLIB) is served with its advice. More than
  one `@AcpAgent` bean fails the startup with "Found 2 @AcpAgent beans [...]", naming them. An
  application without one, such as a client, gets no agent and no agent transport.
- **One bean for every session.** Over HTTP each connection gets its own agent, and every one of
  them calls the same bean. Make its handler methods thread-safe, and keep per-session state keyed
  by session ID.
- **Extension beans.** `AcpInterceptor`, `ArgumentResolver` and `ReturnValueHandler` beans are
  added to the agent in bean order.
- **Your own beans win.** An `AcpAgentTransport` bean of your own replaces stdio, for example the
  agent side of `acp-test`'s `InMemoryTransportPair` in a test. An `AcpAgentFactory`, a
  `StreamableHttpAcpAgentTransport`, or a bean named `acpServletRegistration` of your own replaces
  the one the autoconfiguration creates.
- **Lifecycle.** The agent starts with the context and stops with it, waiting at most 30 seconds
  for a graceful stop.

## A client

Set one transport's command or URI, and the application gets an `AcpAsyncClient` and an
`AcpSyncClient`. The sync client is a facade over the async one, so both share one session on one
connection. Inject either.

```properties
spring.acp.client.transport.stdio.command=my-agent
spring.acp.client.transport.stdio.args=--acp
```

```java
@Component
class SessionUpdates implements AcpClientCustomizer {
    @Override
    public void customize(AcpClient.AsyncSpec spec) {
        spec.sessionUpdateHandler(notification -> {
            if (notification.update() instanceof AgentMessageChunk chunk
                    && chunk.content() instanceof TextContent text) {
                System.out.print(text.text());
            }
            return Mono.empty();
        });
    }
}

@Component
class Ask implements CommandLineRunner {
    private final AcpSyncClient client;

    Ask(AcpSyncClient client) {
        this.client = client;
    }

    @Override
    public void run(String... args) {
        client.initialize();
        String sessionId = client.newSession(new NewSessionRequest("/workspace", List.of())).sessionId();
        client.prompt(new PromptRequest(sessionId, List.of(new TextContent("Hello"))));
    }
}
```

- **Connecting.** Creating the client bean connects the transport, and for stdio starts the agent
  process. `initialize()` then performs the ACP handshake.
- **Customizers.** `AcpClientCustomizer` beans (`com.agentclientprotocol.sdk.integration`, the same
  type in the Micronaut and Quarkus integrations) are applied to the client's builder in bean order.
  Register the session-update handler, the permission handler, and the file system, terminal and
  elicitation handlers there. Without a session-update handler of your own, session updates are
  logged at DEBUG.
- **Capabilities.** The client advertises none by default. Turn one on with its
  `spring.acp.client.capabilities.*` property and register its handler in a customizer. A
  capability turned on without its handler fails the startup, naming the property.
- **Closing.** When the context closes, the client is closed once. Pending notifications are
  delivered, waiting at most the request timeout plus 10 seconds, then the transport is closed.
- **Your own beans win.** An `AcpClientTransport`, `AcpAsyncClient` or `AcpSyncClient` bean of your
  own replaces the one the autoconfiguration creates.

## Transports

### Agent: `spring.acp.agent.transport.type`

| Value | Where the agent is served |
|---|---|
| `stdio`, or unset | Standard input and output. A web application serves the agent on stdio too, unless you set `http`. |
| `http` | ACP Streamable HTTP. It needs `acp-streamable-http-jetty`; without it the startup fails, naming the module. See the next table for where it is served. |
| `websocket` | The same as `http`. The endpoint takes WebSocket upgrades on its path where it can (see the next table). |

The value is read in any case. With `http` or `websocket`, the kind of application decides where
the endpoint runs:

| Application | Endpoint |
|---|---|
| Servlet web application (Spring MVC) | `StreamableHttpAcpServlet`, mounted on the application's own server at `spring.acp.agent.transport.http.path` (`server.port`). HTTP and SSE only: it takes no WebSocket upgrades, even with `type=websocket`. The `listener.*` properties are ignored. |
| Not a web application | The SDK's Jetty listener (`StreamableHttpAcpAgentTransport`) on `spring.acp.agent.transport.http.listener.port`: HTTP/1.1, cleartext HTTP/2 and WebSocket upgrades on one path. It starts with the context and stops with it, waiting at most 30 seconds. |
| Reactive web application (WebFlux) | Not supported. The startup fails: "The ACP HTTP transport needs a servlet web application or the standalone listener (acp-streamable-http-jetty); WebFlux is not supported". |

**Servlet shutdown.** Each client connection holds an open SSE response, which Spring Boot's
graceful shutdown counts as an active request and would wait for, up to
`spring.lifecycle.timeout-per-shutdown-phase` (30 seconds by default). A `SmartLifecycle` in the
default phase therefore closes the servlet's ACP connections first, waiting at most 30 seconds.
It stops before graceful shutdown and the web server do.

### Client: `spring.acp.client.transport.*`

| Transport | Set | Client transport |
|---|---|---|
| `stdio` | `stdio.command`, with `stdio.args` and `stdio.env.*` | `StdioAcpClientTransport`: starts the agent process |
| `websocket` | `websocket.uri`, such as `ws://localhost:8080/acp` | `WebSocketAcpClientTransport` |
| `http` | `http.uri`, such as `http://localhost:8080/acp` | `StreamableHttpAcpClientTransport` |

The rules for choosing one, the same in every framework integration:

- With none of `type`, `stdio.command`, `websocket.uri` and `http.uri` set, the application gets no
  transport and no client.
- `type` unset: the transport is the one whose command or URI is set. With more than one set, the
  startup fails, naming them.
- `type` set: it wins. If its own command or URI is missing, the startup fails, naming that
  property.

The SDK's listener takes both `websocket` and `http` clients. An agent mounted as a servlet takes
`http` clients only.

## Properties

Durations take Spring's forms (`30s`, `500ms`, `PT1M`), and sizes take `DataSize` forms (`16MB`).
Most properties have no default of their own: unset, they keep the SDK's default, shown here. A
value the SDK refuses, such as a negative duration, fails the startup.

### `spring.acp.agent.*`

| Property | Default | Meaning, and what it maps to |
|---|---|---|
| `enabled` | `true` | Serve the `@AcpAgent` bean. `false`: see [Switching off](#switching-off). |
| `request-timeout` | `60s` (SDK) | How long a request the agent sends to the client, such as a permission request or a file read, waits for its answer. `AcpAgentSupport.Builder.requestTimeout`. |
| `cancel-grace-period` | `60s` (SDK) | How long a `@Prompt` method has to return after `session/cancel`. When it passes, the agent answers the prompt `cancelled` itself and interrupts the method's thread. `0` for no limit. `AcpAgentSupport.Builder.cancelGracePeriod`. |
| `max-prompt-duration` | none (SDK) | How long a prompt turn may run before the agent answers it with error `-32800`. `0` for no limit. `AcpAgentSupport.Builder.maxPromptDuration`. |
| `shutdown-on-transport-end` | `true` | Close the application context when a single transport (stdio, or an `AcpAgentTransport` bean of your own) ends on its own. An HTTP endpoint never ends because one client leaves. |
| `handler-executor` | unset | The executor the handler methods run on; see [Handler executor](#handler-executor-and-virtual-threads). `AcpAgentSupport.Builder.handlerExecutor`. |
| `transport.type` | `stdio` | `stdio`, `http` or `websocket`; see [Transports](#transports). |
| `transport.http.path` | `/acp` | The endpoint's path, on the application's server or on the listener. |
| `transport.http.max-post-body-size` | `16MB` (SDK) | The largest inbound message. A larger POST body is answered 413, and a larger WebSocket text message closes the connection. Transport option `maxPostBodyBytes`. |
| `transport.http.keep-alive-interval` | `15s` (SDK) | Interval between keep-alive comments on open SSE streams. `0` turns them off. Transport option `keepAliveInterval`. |
| `transport.http.mailbox-capacity` | `1024` (SDK) | Events one SSE stream keeps while no client is reading it, for the client's reconnect. One more closes the connection. Transport option `mailboxCapacity`. |
| `transport.http.max-pending-sse-events` | `1024` (SDK) | Events queued for a client reading an SSE stream. One more detaches that client and keeps the events for its next GET. Transport option `maxPendingSseEvents`. |
| `transport.http.max-web-socket-pending-frames` | `1024` (SDK) | Frames queued for one WebSocket connection. One more closes it. Transport option `maxWebSocketPendingFrames`. |
| `transport.http.max-provisional-sessions` | `64` (SDK) | Session streams a connection may open before the agent knows the session, as before `session/load`. A further one is refused. Transport option `maxProvisionalSessions`. |
| `transport.http.shutdown-timeout` | `5s` (SDK) | How long closing the endpoint waits for its connections to close gracefully before closing the rest at once. Transport option `shutdownTimeout`. |
| `transport.http.allowed-origins` | none | Browser origins accepted besides `http(s)://localhost`, `127.0.0.1` and `[::1]` (any port), such as `https://app.example.com`; `*` for any. Any other `Origin` is answered 403, over HTTP and on the WebSocket handshake; a request without one is served. Transport option `allowedOrigins`. |
| `transport.http.listener.host` | loopback | The address the SDK listener binds. Unset binds `127.0.0.1` and `::1` only; `0.0.0.0` exposes the agent on every interface, an explicit opt-in, since the endpoint has no authentication of its own. Ignored in a servlet web application, which uses `server.address`. Transport option `host`. |
| `transport.http.listener.port` | `8080` | The SDK listener's port. `0` picks a free one. Ignored in a servlet web application, which uses `server.port`. |
| `transport.http.listener.max-concurrent-streams-per-connection` | `1024` (SDK) | HTTP/2 streams one client connection may hold open on the listener; each open SSE stream holds one. Transport option `maxConcurrentStreamsPerConnection`. |

The agent properties map onto `AcpAgentSettings` in `acp-integration`, and the `transport.http.*`
limits onto `StreamableHttpAcpAgentTransportOptions`, which documents each range.

### `spring.acp.client.*`

| Property | Default | Meaning, and what it maps to |
|---|---|---|
| `request-timeout` | `60s` (SDK) | How long the client waits for the agent to answer a request other than a prompt. `AcpClient.AsyncSpec.requestTimeout`. |
| `prompt-timeout` | none (SDK) | How long a prompt turn may take. When it passes, `prompt` fails with a `TimeoutException` and the client sends the agent `$/cancel_request`. `0` for no limit. `AcpClient.AsyncSpec.promptTimeout`. |
| `transport.type` | inferred | `stdio`, `websocket` or `http`, in any case; see [Transports](#client-springacpclienttransport). |
| `transport.stdio.command` | | The command that starts the agent process. Selects the stdio transport. |
| `transport.stdio.args` | none | The command's arguments, in order. |
| `transport.stdio.env.*` | none | Variables added to the agent process's environment. The process inherits the application's whole environment, and these add to it or replace a variable of the same name. |
| `transport.websocket.uri` | | The agent's WebSocket endpoint. Selects the WebSocket transport. |
| `transport.websocket.connect-timeout` | `10s` | How long the WebSocket handshake may take. This replaces the transport's own default. |
| `transport.http.uri` | | The agent's Streamable HTTP endpoint. Selects the HTTP transport. |
| `capabilities.read-text-file` | `false` | Advertise `fs/read_text_file`. Needs a read-file handler. |
| `capabilities.write-text-file` | `false` | Advertise `fs/write_text_file`. Needs a write-file handler. |
| `capabilities.terminal` | `false` | Advertise the `terminal/*` methods. Needs the terminal handlers. |
| `capabilities.elicitation-form` | `false` | Advertise form-mode `elicitation/create`. Needs an elicitation handler. |
| `capabilities.elicitation-url` | `false` | Advertise URL-mode `elicitation/create`. Needs an elicitation handler. |
| `capabilities.boolean-config-options` | `false` | Advertise that the client accepts boolean session config options. |

The client properties map onto `AcpClientSettings` in `acp-integration`. The Micronaut
(`acp.*`) and Quarkus (`quarkus.acp.*`) integrations map their own keys onto the same settings, so
each property means the same there.

## Switching off

- **Agent.** `spring.acp.agent.enabled=false` creates no agent beans: no agent factory, no stdio
  transport, no HTTP endpoint, and no agent on an `AcpAgentTransport` bean of your own. Any number
  of `@AcpAgent` beans is then allowed.
- **Client.** The client has no `enabled` property. It exists only when a
  `spring.acp.client.transport.*` property is set: leave them all unset for no client.

## Handler executor and virtual threads

`spring.acp.agent.handler-executor` chooses the threads the agent's handler methods run on:

- **Unset (the default).** With `spring.threads.virtual.enabled=true` (JDK 21 and later), the
  context's `applicationTaskExecutor`, which starts a virtual thread per handler call. Otherwise the
  SDK's pool of platform threads, on every JDK: the starter follows Spring Boot's opt-in, not the
  SDK's own default of virtual threads on JDK 21. Without virtual threads, Spring Boot's
  `applicationTaskExecutor` is a pool of 8 threads by default, which would cap the prompts served at
  once.
- **The name of an `Executor` bean.** That bean. A plain `Executor`, such as a `TaskExecutor`, is
  adapted to an `ExecutorService`, and cancelling a handler still interrupts its thread. Handler
  methods block, so the executor must allow blocking. A name that matches no bean, or a bean that
  is no `Executor`, fails the startup.
- **`none`, in any case.** The SDK's pool of platform threads.

The same opt-in decides the threads of the SDK's listener and of the WebSocket and Streamable HTTP
client transports: with virtual threads on, they run on the `applicationTaskExecutor` and create no
pool of their own (a JDK `HttpClient` keeps its one selector thread, Jetty's virtual-thread pool one
parked platform thread, and the SDK one JVM-wide timer thread, `acp-timeout`); otherwise they keep
their pools of platform threads. On JDK 21 to 23 a virtual thread that blocks inside a
`synchronized` block pins its carrier thread; JDK 24 removed that (JEP 491).

## Limitations

- **WebFlux is not supported.** With `spring.acp.agent.transport.type=http` or `websocket`, a
  reactive web application fails at startup. Use a servlet web application, or no web application
  with the SDK's listener.
- **No WebSocket in a servlet web application.** There `type=websocket` serves HTTP/SSE only, like
  `http`. A WebSocket client needs the SDK's listener, in an application that is not a web
  application.
- **One agent per application.** A second `@AcpAgent` bean fails the startup.

## Migrating from `org.springaicommunity:acp-spring-boot-starter` 0.12.0

The former `spring-ai-community/acp-autoconfig` project is now these two modules. In short:

- Coordinates: `org.springaicommunity:acp-spring-boot-starter` becomes
  `com.agentclientprotocol:acp-spring-boot-starter`, with the SDK's version. Drop any separate ACP
  SDK version pin.
- Imports: `com.agentclientprotocol.autoconfigure.*` becomes
  `com.agentclientprotocol.sdk.spring.boot.autoconfigure.*`. `AcpClientCustomizer` moves to
  `com.agentclientprotocol.sdk.integration`.
- The `spring.acp.*` keys are unchanged, except that the listener-only agent properties move under
  `spring.acp.agent.transport.http.listener.*` and the `spring.acp.agent.transport.websocket.*`
  properties are removed.

The full list of behaviour changes is in [CHANGELOG.md](../CHANGELOG.md), in the entry "Spring Boot
autoconfiguration and starter, now part of the SDK" under Added. The SDK's other breaking changes
in that release apply too.

## See also

- The root [README](../README.md): the SDK, its modules and its transports.
- [`acp-agent-support`](../acp-agent-support/README.md): the annotation programming model.
- [`acp-micronaut`](../acp-micronaut/README.md) and [`acp-quarkus`](../acp-quarkus/README.md): the
  same integration for Micronaut and Quarkus.
- [`acp-integration`](../acp-integration/README.md): the framework-neutral half these build on.
