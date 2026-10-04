# acp-integration

The framework-neutral half of the ACP Java SDK's framework integrations. The Spring Boot
autoconfiguration, the Micronaut integration and the Quarkus extension are each a thin layer over
this module: they bind their own configuration, find the `@AcpAgent` bean their own way, and call
the hosts here from their own lifecycle hooks. Everything that does not depend on a framework lives
here, once.

Use it to integrate ACP with another container. An application on Spring Boot, Micronaut or
Quarkus does not use it directly, except for `AcpClientCustomizer`.

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-integration</artifactId>
</dependency>
<!-- For an agent served over Streamable HTTP or WebSocket -->
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-streamable-http-jetty</artifactId>
</dependency>
```

The module depends on `acp-core` and `acp-agent-support`, with `acp-streamable-http-jetty`
optional, and on no framework; an ArchUnit test enforces both, and that the SDK below does not
depend on it. Package `com.agentclientprotocol.sdk.integration`, null-marked.

## Settings

`AcpAgentSettings` and `AcpClientSettings` are immutable records with builders. A value left unset
keeps the SDK's default, so no default is decided twice. A framework binds its configuration onto
the builders (Quarkus `@ConfigMapping`, Spring and Micronaut `@ConfigurationProperties`), or reads
plain key/value configuration with `from(SettingsSource, prefix)`:

| Agent key | Default |
|---|---|
| `enabled` | `true` |
| `request-timeout`, `cancel-grace-period`, `max-prompt-duration` | SDK defaults |
| `shutdown-on-transport-end` | `true`: stop the application when stdio input ends |
| `transport.type` | `stdio`; `http` (or `websocket`, the same) for Streamable HTTP and WebSocket. The SDK's listener takes WebSocket upgrades on the endpoint's path; the servlet in a framework's Servlet container does not, so there `websocket` serves HTTP/SSE only, unless the framework routes the upgrades itself (Quarkus does) |
| `transport.http.path` | `/acp` |
| `transport.http.max-post-body-size`, `keep-alive-interval`, `mailbox-capacity`, `max-pending-sse-events`, `max-web-socket-pending-frames`, `max-provisional-sessions`, `shutdown-timeout` | SDK defaults |
| `transport.http.listener.port` | `8080`, the SDK listener only; `0` for an ephemeral port |
| `transport.http.listener.max-concurrent-streams-per-connection` | SDK default |

| Client key | Default |
|---|---|
| `request-timeout` | SDK default |
| `prompt-timeout` | none |
| `transport.type` | inferred (below) |
| `transport.stdio.command`, `args`, `env` | the agent process to start |
| `transport.websocket.uri`, `connect-timeout` | `10s` |
| `transport.http.uri` | |
| `capabilities.read-text-file`, `write-text-file`, `terminal`, `elicitation-form`, `elicitation-url`, `boolean-config-options` | `false` |

Advertise a capability only together with the handler for it, registered through an
`AcpClientCustomizer`.

## Transports

`AcpClientTransports.create(settings, prefix)` applies one rule in every framework: an explicit
`transport.type` wins and fails without its command or URI; otherwise exactly one of
`stdio.command`, `websocket.uri` and `http.uri` selects the transport, and more than one fails,
naming them; with none there is no client. Error messages name the framework's own keys.

`AcpAgentTransports.stdio()` is the stdio agent transport. `AcpListeners` creates the endpoints
of the optional `acp-streamable-http-jetty` module: `listener(settings, factory)` for the SDK's own
listener (HTTP, cleartext HTTP/2 and WebSocket on one path) and `servlet(settings, factory)` for a
framework's Servlet container; `isListenerAvailable()` says whether the listener and its Jetty
server are on the classpath. The module's types appear in `AcpListeners`, `AcpListenerHost`,
`AcpServletHost` and `AcpAgentSettings.toOptions`. A framework without the module still loads
these classes, `AcpAgentSettings` included, because the JVM resolves those types only when code
that uses them runs: call them only when the agent is served over HTTP, and call
`isListenerAvailable()` before `listener` to report a missing module in the framework's own
words.

## The agent

```java
List<AgentCandidate<?>> candidates = ...;   // the container's @AcpAgent beans: user class, instance supplier
AcpAgentDiscovery.requireSingle(candidates, "my.acp.agent.enabled").ifPresent(agent -> {
    AcpAgentSupport.Builder builder = AcpAgents.builder(agent, settings, interceptors, resolvers, handlers);
    AcpHost host = settings.servesHttp()
            ? new AcpListenerHost(AcpListeners.listener(settings, builder.buildFactory()))
            : new AcpAgentHost(builder.transport(AcpAgentTransports.stdio()).build(),
                    settings.shutdownOnTransportEnd() ? container::close : () -> { });
    host.start();                            // from the container's start hook
    // host.stop(Duration.ofSeconds(30))     // from its stop hook, or a JVM shutdown hook
});
```

- `AcpAgentDiscovery.requireSingle`: none is a client-only application, more than one an error
  naming them. A candidate carries the user class from the container's metadata, never a proxy
  class, and a supplier of the bean, which may be a proxy and keeps its advice. A build-time form
  takes class names (a Jandex index) and loads nothing.
- `AcpAgents.builder`: handlers discovered on the user class, the settings' timeouts, then the
  interceptors, argument resolvers and return value handlers in order.
- `AcpHost`: `start()`, `stopGracefully()`, `stop(Duration)` (safe from a shutdown hook, also
  while `start()` runs), `termination()`, `port()`, and `holdJvmUntilTermination()` for an
  application with nothing else keeping the JVM up. Nothing blocks except `stop`, so a framework
  whose `main` returns can host an agent too.
- `AcpAgentHost`: one agent on one transport, on the SDK's own lifecycle (`AcpAgentSupport`'s
  `start()` and `close()`, its agent's `awaitTermination()`). When the transport ends by itself,
  the `onTransportEnd` action given at construction runs once, on a thread of the host's own,
  never when the host stopped the agent itself.
- `AcpListenerHost`: the SDK listener, one agent per connection; it ends only when stopped.
- `AcpServletHost.closeBeforeShutdown`: closes the servlet's connections before the container's
  graceful shutdown, which would otherwise wait for every open SSE response.

## The client

`AcpClients.async(transport, settings, customizers)` builds the one async client: capabilities,
request and prompt timeouts, a session-update handler that logs at DEBUG, then the
`AcpClientCustomizer`s in order. Building the client connects the transport (for stdio, it starts
the agent process); a stdio command that cannot be started, or a transport already connected,
fails the build. The ACP handshake waits for the application's `initialize()`.
`AcpClients.sync(async)` is the sync facade over that same client
(one session on one connection). `AcpClientHost` closes it once, gracefully, then at once after
its timeout (`settings.closeTimeout()`); the framework must not close the client or its transport
again through an inferred destroy method.

## Not here

The Mutiny `Uni` and `Multi` return types stay in `acp-quarkus`; the `CompletionStage` and
`Publisher` ones are in `acp-agent-support`. Planned after 0.80.0: a container-neutral WebSocket
connection API, so Vert.x, Netty and other servers route WebSocket upgrades to the SDK without
Jetty, and a Jetty-free `acp-streamable-http-servlet` module.
