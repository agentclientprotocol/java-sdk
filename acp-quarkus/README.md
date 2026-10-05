# ACP Quarkus

A Quarkus extension for the ACP Java SDK. Annotate one class with `@AcpAgent` and Quarkus serves it
over stdio, or over Streamable HTTP and WebSocket on the application's own HTTP server. Inject
`AcpSyncClient` or `AcpAsyncClient` to talk to an agent configured in `application.properties`.
JVM mode; native images are not supported in 0.80.0.

## Installation

```xml
<dependencyManagement>
    <dependencies>
        <!-- Optional, before the Quarkus BOM: the Jackson the SDK is built with (see "Versions"). -->
        <dependency>
            <groupId>com.fasterxml.jackson</groupId>
            <artifactId>jackson-bom</artifactId>
            <version>2.22.3</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>io.quarkus.platform</groupId>
            <artifactId>quarkus-bom</artifactId>
            <version>3.40.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.agentclientprotocol</groupId>
        <artifactId>acp-quarkus</artifactId>
        <version>${acp.version}</version>
    </dependency>
</dependencies>
```

## An agent

```java
@AcpAgent
public class GreeterAgent {

    private final Greeting greeting;          // any bean: constructor injection works

    GreeterAgent(Greeting greeting) {
        this.greeting = greeting;
    }

    @Prompt
    PromptResponse prompt(PromptRequest request, SyncPromptContext context) {
        context.sendMessage(greeting.greet(request));
        return PromptResponse.endTurn();
    }
}
```

- `@AcpAgent` alone makes the class a bean, a singleton unless it declares a scope. The class is
  found at build time; a second `@AcpAgent` class fails the build with both names.
- `AcpInterceptor`, `ArgumentResolver` and `ReturnValueHandler` beans are added to the agent.
- Handlers may return Mutiny types: a `Uni` of what the handler could return directly, and from
  `@Prompt` a `Multi` of `String`, `ContentBlock` or `SessionUpdate` items, each sent as it is
  emitted, ending the turn when the stream completes. The handler's thread waits for them.
- Handlers block, so they run off the event loop: by default on Quarkus' `ManagedExecutor` worker
  pool, which carries the contexts MicroProfile Context Propagation propagates from the submitting
  thread (the CDI request context, the security identity, a transaction, the OpenTelemetry
  context). `quarkus.acp.handler-executor=virtual` opts in to Quarkus' virtual-thread executor on
  JDK 21 and later, a virtual thread per call (as `managed` before JDK 21). It propagates none of
  those contexts: a `@RequestScoped` bean needs `@ActivateRequestContext` on the handler method.
  Virtual threads on JDK 21 to 23 pin their carrier when they block inside `synchronized`; JDK 24
  removed that (JEP 491). The client's WebSocket and Streamable HTTP transports run on the same
  executor.
- Package the application as usual (`quarkus-run.jar`) and point the editor at
  `java -jar target/quarkus-app/quarkus-run.jar`.

### Over stdio (the default)

Standard output carries the protocol, so a stdio build sets these defaults (your own settings win):
`quarkus.log.console.stderr=true`, `quarkus.banner.enabled=false`, `quarkus.http.host-enabled=false`.
Never print to `System.out`. The application exits when the client closes standard input
(`quarkus.acp.agent.shutdown-on-transport-end=false` keeps it running). Dev mode (`quarkus dev`)
owns the terminal's standard input, so develop a stdio agent in tests (an application bean of type
`AcpAgentTransport`, such as an in-memory one from `acp-test`, replaces stdio) or over HTTP.

### Over HTTP and WebSocket

```properties
quarkus.acp.agent.transport.type=http
quarkus.acp.agent.transport.http.path=/acp
```

The agent is served at `/acp` on the Quarkus HTTP server (`quarkus.http.port`, under
`quarkus.http.root-path`): Streamable HTTP, SSE and WebSocket upgrades on one Vert.x route of the
Quarkus router, with no servlet container and no second server. The route sits behind Quarkus's
authentication and permission checks, so HTTP security policies apply to `/acp`, the WebSocket
handshake included:

```properties
quarkus.http.auth.permission.acp.paths=/acp
quarkus.http.auth.permission.acp.policy=authenticated
```

Each connection runs its own agent runtime over the one bean, so the bean's handlers must be
thread-safe. On shutdown the endpoint drains first, within
`quarkus.acp.agent.transport.http.shutdown-timeout`: SSE streams get a closing comment and
complete, WebSockets close with 1001.

An HTTP build raises these Quarkus defaults to what the SDK's own listener admits (your own settings
win): `quarkus.http.limits.max-body-size=16M`,
`quarkus.http.websocket-server.max-message-size` and `max-frame-size` to 16 MB, and
`quarkus.http.limits.max-concurrent-streams=1024` (each SSE stream is one HTTP/2 stream). Raise them
too if you raise `max-post-body-size` past 16 MB.

## A client

```properties
quarkus.acp.client.transport.stdio.command=my-agent
quarkus.acp.client.transport.stdio.args=--acp
```

```java
@Inject AcpSyncClient client;

@Singleton
class Updates implements AcpClientCustomizer {        // optional; @Priority orders several
    public void customize(AcpClient.AsyncSpec spec) {
        spec.sessionUpdateHandler(notification -> { /* ... */ return Mono.empty(); });
    }
}
```

`AcpClientCustomizer` is `com.agentclientprotocol.sdk.integration.AcpClientCustomizer`, the same
type in every framework integration. The client beans are created when first injected and closed when the application stops; a bean of
your own of the same type replaces each. Client callbacks run on SDK threads, not on a Vert.x
context: from a `Uni` built over a client call, hop back with `emitOn` when you need the context.

## Configuration

Build time (fixed when the application is built):

| Property | Default | |
|---|---|---|
| `quarkus.acp.agent.enabled` | `true` | serve the `@AcpAgent` bean |
| `quarkus.acp.agent.transport.type` | `stdio` | `stdio` or `http` (`websocket` means the same as `http`) |
| `quarkus.acp.agent.transport.http.path` | `/acp` | endpoint path (Streamable HTTP and WebSocket) |

Run time:

| Property | Default | |
|---|---|---|
| `quarkus.acp.handler-executor` | `managed` | `managed` or `virtual` (JDK 21+, else as `managed`): where handlers and client transports run |
| `quarkus.acp.agent.request-timeout` | SDK default | agent requests to the client |
| `quarkus.acp.agent.cancel-grace-period` | SDK default | |
| `quarkus.acp.agent.max-prompt-duration` | none | |
| `quarkus.acp.agent.shutdown-on-transport-end` | `true` | stdio: exit when input ends |
| `quarkus.acp.agent.transport.http.max-post-body-size` | 16M | POST body and WebSocket message |
| `quarkus.acp.agent.transport.http.keep-alive-interval` | 15s | |
| `quarkus.acp.agent.transport.http.mailbox-capacity` | 1024 | |
| `quarkus.acp.agent.transport.http.max-pending-sse-events` | 1024 | |
| `quarkus.acp.agent.transport.http.max-web-socket-pending-frames` | 1024 | |
| `quarkus.acp.agent.transport.http.max-provisional-sessions` | 64 | |
| `quarkus.acp.agent.transport.http.shutdown-timeout` | 5s | |
| `quarkus.acp.agent.transport.http.allowed-origins` | none | browser origins accepted besides `localhost`, `127.0.0.1` and `[::1]`; any other `Origin` is answered 403 (HTTP and WebSocket handshake); `*` for any. The bind address is Quarkus's own, `quarkus.http.host` |
| `quarkus.acp.client.request-timeout` | SDK default | |
| `quarkus.acp.client.transport.type` | inferred | `stdio`, `websocket` or `http`; unset: the one of `stdio.command`, `websocket.uri` and `http.uri` that is set; with several set, it is required |
| `quarkus.acp.client.transport.stdio.command`, `.args`, `.env.<NAME>` | | the agent process |
| `quarkus.acp.client.transport.websocket.uri`, `.connect-timeout` | `10s` | |
| `quarkus.acp.client.transport.http.uri` | | |
| `quarkus.acp.client.capabilities.read-text-file`, `.write-text-file`, `.terminal`, `.elicitation-form`, `.elicitation-url`, `.boolean-config-options` | `false` | advertise only what a customizer registers handlers for |

Compared with the Spring Boot starter's `spring.acp.*`: the HTTP port is `quarkus.http.port`, and the
listener's `max-concurrent-streams-per-connection` is `quarkus.http.limits.max-concurrent-streams`.

## Versions

Quarkus 3.40.1 manages Jackson 2.21.7; the SDK is built with Jackson 2.22.3. Both lines carry the
2026 Jackson fixes (OSV lists no advisory for either, 2026-10-03), and the extension's integration
tests and the cross-SDK cells run on the Quarkus-managed 2.21.7. To run exactly the SDK's version,
import `jackson-bom` before the Quarkus BOM, as above; without it, Quarkus's version applies, which
is also supported. Reactor (3.8.7) is the same in both BOMs, and Netty is Quarkus's. The extension
excludes the Jetty jars of `acp-streamable-http-jetty`, whose servlet does not use Jetty.
