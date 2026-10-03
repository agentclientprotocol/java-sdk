# acp-integration: interface sketch (for review, not built)

Framework-neutral logic shared by the Spring Boot, Quarkus and Micronaut integrations. The module depends on
acp-core and acp-agent-support, with acp-streamable-http-jetty optional, and on no framework (an ArchUnit rule
enforces this). Package `com.agentclientprotocol.sdk.integration`. Every type is `@NullMarked`.

Sources: the generic half of `acp-spring-boot-autoconfigure` on this branch, and section 1 of the release
manager's framework-neutrality review (2026-10-03). Each type below names the Spring class it comes from.

Not in the 0.80.0 scope: build-time handler registration, native-image metadata and a non-blocking
annotation runtime. Those are SDK hooks (review section 2) and live in acp-agent-support or acp-core, not here.

Quarkus and Micronaut integrations are required for 0.80.0, not previews (owner). Reviewed by acp-dev-steward
on 2026-10-03; its changes are folded in below. Next come the Quarkus and Micronaut reviews. Extraction
waits for those reviews and for devex slice 1.

## Settings

Immutable records with builders. Each framework binds its own configuration onto them; `from(..)` lets any
key/value source feed the same keys.

```java
public enum AcpTransportType { STDIO, WEBSOCKET, HTTP }

public record AcpAgentSettings(
        boolean enabled,                        // default true
        @Nullable Duration requestTimeout,      // null = SDK default (api1 unifies it); never hardcoded
        @Nullable Duration cancelGracePeriod,   // SDK default when null (new: the review)
        @Nullable Duration maxPromptDuration,   // SDK default when null (new: the review)
        boolean shutdownOnTransportEnd,         // default true
        AcpTransportType transport,             // STDIO or HTTP; default STDIO
        Http http) {

    public record Http(int port, String path,   // 8080, "/acp"; port 0 = ephemeral
            @Nullable Long maxPostBodyBytes, @Nullable Duration keepAliveInterval,
            @Nullable Integer mailboxCapacity, @Nullable Integer maxPendingSseEvents,
            @Nullable Integer maxWebSocketPendingFrames, @Nullable Integer maxProvisionalSessions,
            @Nullable Integer maxConcurrentStreamsPerConnection, @Nullable Duration shutdownTimeout) {
        public StreamableHttpAcpAgentTransportOptions toOptions();   // unset → SDK default
    }

    public static Builder builder();
    /**
     * Keys relative to prefix, kebab-case only, e.g. "request-timeout", "transport.type",
     * "transport.http.port". Normalising the framework's own key forms to kebab-case is the binder's job.
     */
    public static AcpAgentSettings from(Function<String, @Nullable String> lookup, String prefix);
}

public record AcpClientSettings(
        @Nullable Duration requestTimeout,      // null = SDK default (api1 unifies it); never hardcoded
        @Nullable Duration promptTimeout,       // none by default; separate from requestTimeout (api1 A4)
        @Nullable AcpTransportType transport,   // null = infer (see AcpClientTransports)
        Stdio stdio, WebSocket websocket, Http http,
        Capabilities capabilities) {

    public record Stdio(@Nullable String command, List<String> args, Map<String, String> env) {}
    public record WebSocket(@Nullable URI uri, Duration connectTimeout) {}   // 10s
    public record Http(@Nullable URI uri) {}
    /**
     * All false by default. Advertise only what the application registers handlers for: from api1 (A7)
     * the client fails at build() when a capability is advertised without its handler, so a framework
     * layer turns a capability on only alongside the handler the application provides.
     */
    public record Capabilities(boolean readTextFile, boolean writeTextFile, boolean terminal,
            boolean elicitationForm, boolean elicitationUrl, boolean booleanConfigOptions) {}

    public static Builder builder();
    /** Kebab-case keys only, as for AcpAgentSettings.from. */
    public static AcpClientSettings from(Function<String, @Nullable String> lookup, String prefix);
}
```

## Transports from settings

```java
public final class AcpClientTransports {
    /**
     * An explicit type wins; otherwise stdio.command, then websocket.uri, then http.uri.
     * Empty when none is set (a client-only app with no client configured).
     * An explicit type without its property throws IllegalStateException naming the property.
     */
    public static Optional<AcpClientTransport> create(AcpClientSettings settings);
}

public final class AcpAgentTransports {
    /** STDIO: a stdio transport over System.in/out. */
    public static AcpAgentTransport stdio();
    /** HTTP: the SDK listener; throws naming acp-streamable-http-jetty when it is absent. */
    public static StreamableHttpAcpAgentTransport listener(AcpAgentSettings settings, AcpAgentFactory factory);
    /** HTTP inside the framework's own Servlet container (no WebSocket). */
    public static StreamableHttpAcpServlet servlet(AcpAgentSettings settings, AcpAgentFactory factory);
}
```

## Agent assembly

```java
public final class AcpAgentDiscovery {
    /**
     * The one @AcpAgent among the candidates: none → Optional.empty() (a client-only app);
     * more than one → IllegalStateException listing them. Candidates carry the *user* class
     * from the container's metadata, never a proxy class (CGLIB, ArC, Micronaut AOP).
     */
    public static Optional<AgentCandidate> requireSingle(Collection<AgentCandidate> candidates);

    public record AgentCandidate(String name, Class<?> userClass, Supplier<?> instance) {}
}

public final class AcpAgents {
    /**
     * The SDK's discovery entry point taking the user class and an instance supplier (devex keeps a
     * (Class<?> userClass, Supplier<?> instance) form; use whatever devex lands, proxy-safe and with
     * superclass handlers), with the settings' timeouts and the given interceptors, argument resolvers and
     * return-value handlers, in order.
     */
    public static AcpAgentSupport.Builder builder(AgentCandidate agent, AcpAgentSettings settings,
            List<AcpInterceptor> interceptors, List<ArgumentResolver> resolvers,
            List<ReturnValueHandler> returnValueHandlers);
}
```

The DEBUG-logging default and the rest of the client side:

```java
@FunctionalInterface
public interface AcpClientCustomizer { void customize(AcpClient.AsyncSpec spec); }   // moved from Spring

public final class AcpClients {
    /**
     * Capabilities, request timeout and prompt timeout from settings (prompt timeout: TODO pass through when
     * api1 A4 lands), then the customizers in order. Session updates go to a DEBUG-logging consumer only
     * when no customizer registered one: the default is replaced, not added to. That holds in every
     * framework, so an unhandled session update is visible at DEBUG everywhere.
     */
    public static AcpAsyncClient async(AcpClientTransport transport, AcpClientSettings settings,
            List<AcpClientCustomizer> customizers);
    /** The sync facade over the one async client: one session per transport. */
    public static AcpSyncClient sync(AcpAsyncClient async);
}
```

## Lifecycles (the framework calls these from its own hooks)

```java
/**
 * A single-transport agent (stdio). Built on the SDK's own lifecycle: devex's AcpAgentSupport.Builder.run()
 * and fix4's AutoCloseable AcpSyncAgent/AcpAgentSupport, not a copy of their start and stop logic. The
 * host adds onTransportEnd and the host-thread semantics.
 */
public final class AcpAgentHost {
    public AcpAgentHost(AcpAgentSupport agent, AcpAgentTransport transport);
    public void start();
    /** Closes once; a stop started by the host does not trigger onTransportEnd. */
    public void stop();
    /**
     * Runs once, on a host-owned thread (never the transport's), when the transport ends on its own:
     * stdin closed and every reply written. The framework passes "close my container"
     * (Spring: ConfigurableApplicationContext::close; Quarkus: () -> Quarkus.asyncExit(0)).
     */
    public void onTransportEnd(Runnable action);
    /** Blocks until the transport ends; SDK threads are daemons, so a non-web app needs it. */
    public void awaitTermination();
}

/** The HTTP listener. */
public final class AcpListenerHost {
    public AcpListenerHost(StreamableHttpAcpAgentTransport listener, Duration timeout);
    public void start();
    public void stop();
}

/** The servlet inside a framework's Servlet container. Servlet-only: Spring is its user. */
public final class AcpServletHost {
    /**
     * Call before the container's graceful shutdown: each connection holds an open SSE response,
     * which the container would otherwise wait for (Spring: SmartLifecycle at DEFAULT_PHASE).
     */
    public static void closeBeforeShutdown(StreamableHttpAcpServlet servlet, Duration timeout);
}

/** The client: closed once. The framework must not also close it through an inferred destroy method. */
public final class AcpClientHost {
    public AcpClientHost(AcpAsyncClient client);
    public void close();
}
```

## What stays in each framework

- **Spring Boot:** `@AutoConfiguration` and conditions; `@ConfigurationProperties` adapters onto the settings,
  plus metadata; `SmartLifecycle` adapters and their phases; `ServletRegistrationBean`; `destroyMethod=""`;
  `AutoConfiguration.imports`.
- **Quarkus:** see the review's section 3: `@ConfigMapping`, a recorder, Jandex validation, the `Uni` return
  handler and Vert.x context hops. HTTP: a binding into Vert.x HTTP or the SDK listener, still being decided.
  Undertow is not assumed.
- **Micronaut:** see the review's section 3: `@ConfigurationProperties`, `@Factory`, `byStereotype` discovery.
  HTTP: the SDK listener, or the Micronaut agent's own Netty binding if it ships one. Not micronaut-servlet.

## Settled questions (acp-dev-steward, 2026-10-03)

1. `from(lookup, prefix)` takes kebab-case keys only; normalising them is the binder's job.
2. Micronaut uses the SDK listener or its own Netty binding; `AcpServletHost` stays servlet-only.
3. The DEBUG default consumer applies in every framework, and is replaced (not added to) when the
   application registers its own consumer.

## Spring layer changes that follow at extraction

- The `request-timeout` properties stop defaulting to 60s (they become null, meaning the SDK default).
- A new `prompt-timeout` client property.
- The new capability properties.
- The DEBUG consumer becomes replace-not-add. Today the Spring client adds it beside the customizers' consumers.
