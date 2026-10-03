# acp-integration: interface sketch (for review, not built)

Framework-neutral logic shared by the Spring Boot, Quarkus and Micronaut integrations. The module depends on
acp-core and acp-agent-support, with acp-streamable-http-jetty optional, and on no framework (an ArchUnit rule
enforces this). Package `com.agentclientprotocol.sdk.integration`. Every type is `@NullMarked`.

Sources: the generic half of `acp-spring-boot-autoconfigure` on this branch, and section 1 of the release
manager's framework-neutrality review (2026-10-03). Each type below names the Spring class it comes from.

Not in the 0.80.0 scope: build-time handler registration, native-image metadata and a non-blocking
annotation runtime. Those are SDK hooks (review section 2) and live in acp-agent-support or acp-core, not here.

Quarkus and Micronaut integrations are required for 0.80.0, not previews (owner). Reviewed by acp-dev-steward
on 2026-10-03; its changes are folded in below. The Micronaut review (ten items, accepted by acp-dev-steward,
see `acp-java-steward/plans/journal/2026-10-03-micronaut-progress.md`) is folded in too, as is the Quarkus review (`acp-java-steward/plans/journal/2026-10-03-quarkus-progress.md`;
Quarkus serves HTTP on its own server: the SDK servlet on quarkus-undertow plus a Vert.x WebSocket route, no Jetty). Extraction
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
        AcpTransportType transport,             // STDIO or HTTP (WEBSOCKET accepted as a synonym: the
                                                // listener serves both); default STDIO
        Http http) {

    /** The endpoint, wherever it is served: the framework's own server or the SDK listener. */
    public record Http(String path,              // "/acp"
            Limits limits, Listener listener) {}

    /** Message and stream bounds; unset → SDK default. */
    public record Limits(@Nullable Long maxPostBodyBytes, @Nullable Duration keepAliveInterval,
            @Nullable Integer mailboxCapacity, @Nullable Integer maxPendingSseEvents,
            @Nullable Integer maxWebSocketPendingFrames, @Nullable Integer maxProvisionalSessions,
            @Nullable Duration shutdownTimeout) {}

    /** Only for the SDK's own listener; meaningless inside a framework's server. */
    public record Listener(int port,             // 8080; 0 = ephemeral
            @Nullable Integer maxConcurrentStreamsPerConnection) {}

    /** Limits plus, for the listener, its stream limit; unset → SDK default. */
    public StreamableHttpAcpAgentTransportOptions toOptions(boolean listener);

    public static Builder builder();
    /**
     * Optional path: keys relative to prefix, kebab-case only, e.g. "request-timeout", "transport.type",
     * "transport.http.listener.port". Normalising the framework's own key forms to kebab-case is the
     * binder's job. A framework that binds its configuration straight onto the builders (Quarkus
     * @ConfigMapping) does not use it; Micronaut does.
     */
    public static AcpAgentSettings from(SettingsSource source, String prefix);
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
    public static AcpClientSettings from(SettingsSource source, String prefix);
}

/** What a framework's configuration offers; a single lookup cannot build stdio.args or stdio.env. */
public interface SettingsSource {
    @Nullable String get(String key);
    List<String> list(String key);          // empty when absent
    Map<String, String> map(String key);    // empty when absent; map keys keep their case
}
```

## Transports from settings

```java
public final class AcpClientTransports {
    /**
     * An explicit type wins. Otherwise exactly one of stdio.command, websocket.uri and http.uri selects
     * the transport; more than one with no explicit type throws, naming them. That replaces Spring's rule,
     * which picked websocket, then http, then stdio; it goes in the CHANGELOG at extraction, and a test in
     * acp-integration pins it. Empty when none is set (a client-only app).
     * An explicit type without its property throws IllegalStateException naming the property.
     */
    public static Optional<AcpClientTransport> create(AcpClientSettings settings);
}

public final class AcpAgentTransports {
    /** STDIO: a stdio transport over System.in/out. */
    public static AcpAgentTransport stdio();
}

/** Separate so that no always-loaded class names the optional Jetty types. */
public final class AcpListeners {
    /** Whether acp-streamable-http-jetty is on the classpath. */
    public static boolean isListenerAvailable();
    /** HTTP (and WebSocket): the SDK listener; throws naming acp-streamable-http-jetty when it is absent. */
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
    public static Optional<AgentCandidate<?>> requireSingle(Collection<? extends AgentCandidate<?>> candidates);

    /** Build-time discovery (Quarkus/Jandex): the same rule over class names, no classes loaded. */
    public static Optional<String> requireSingle(List<String> classNames);

    public record AgentCandidate<T>(String name, Class<T> userClass, Supplier<? extends T> instance) {}
}

public final class AcpAgents {
    /**
     * The SDK's discovery entry point taking the user class and an instance supplier (devex keeps a
     * (Class<?> userClass, Supplier<?> instance) form; use whatever devex lands, proxy-safe and with
     * superclass handlers), with the settings' timeouts and the given interceptors, argument resolvers and
     * return-value handlers, in order.
     */
    public static <T> AcpAgentSupport.Builder builder(AgentCandidate<T> agent, AcpAgentSettings settings,
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
     * framework, so an unhandled session update is visible at DEBUG everywhere. Needs SDK support:
     * AsyncSpec.sessionUpdateConsumer only adds, so devex or api1 must provide a way to see whether a
     * consumer is set, or a replacing form.
     */
    public static AcpAsyncClient async(AcpClientTransport transport, AcpClientSettings settings,
            List<AcpClientCustomizer> customizers);
    /** The sync facade over the one async client: one session per transport. */
    public static AcpSyncClient sync(AcpAsyncClient async);
}
```

## Lifecycles (the framework calls these from its own hooks)

One host contract. Non-blocking: a framework whose main returns (Micronaut) cannot block in awaitTermination.

```java
public interface AcpHost {
    void start();
    CompletionStage<Void> stopGracefully();
    /** Safe from a JVM shutdown hook, also while start() is still running. */
    void stop(Duration timeout);
    /** Completes when the transport or listener ends. */
    CompletionStage<Void> termination();
    /** The bound port of a listener host; empty for stdio. */
    OptionalInt port();
    /** Optional: one non-daemon thread until termination, for an app with nothing else keeping the JVM up. */
    default void holdJvmUntilTermination() { ... }
}

/**
 * A single-transport agent (stdio). Built on the SDK's own lifecycle: devex's AcpAgentSupport.Builder.run()
 * and fix4's AutoCloseable AcpSyncAgent/AcpAgentSupport, not a copy of their start and stop logic.
 */
public final class AcpAgentHost implements AcpHost {
    /**
     * onTransportEnd is latched: it runs once, on a host-owned thread (never the transport's), also when
     * the transport ended before the host saw it, and not when the host itself stopped the agent. It is
     * fixed at construction, so there is no window in which the end is missed. The framework passes
     * "close my container" (Spring: ConfigurableApplicationContext::close; Quarkus: () -> Quarkus.asyncExit(0)).
     */
    public AcpAgentHost(AcpAgentSupport agent, AcpAgentTransport transport, Runnable onTransportEnd);
}

/** The HTTP listener (in AcpListeners' optional part). */
public final class AcpListenerHost implements AcpHost {
    public AcpListenerHost(StreamableHttpAcpAgentTransport listener);
}

/** The servlet inside a framework's Servlet container: Spring (Tomcat, Jetty) and Quarkus (undertow). */
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
    public CompletionStage<Void> closeGracefully();
    public void close(Duration timeout);
}
```

Not here: the `CompletionStage` and `Publisher` return-value handlers belong in acp-agent-support (devex);
Mutiny's `Uni` and `Multi` stay in acp-quarkus.

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
2. Micronaut uses the SDK listener or its own Netty binding. `AcpServletHost` is for Servlet containers:
   Spring and Quarkus (undertow).
3. The DEBUG default consumer applies in every framework, and is replaced (not added to) when the
   application registers its own consumer.

## After 0.80.0

- A container-neutral WebSocket connection API, so Quarkus (Vert.x), Micronaut (Netty) and others route
  WebSocket upgrades to the SDK without Jetty.
- A Jetty-free `acp-streamable-http-servlet` module, with the servlet apart from the Jetty listener.

## Spring layer changes that follow at extraction

- The `request-timeout` properties stop defaulting to 60s (they become null, meaning the SDK default).
- A new `prompt-timeout` client property.
- The new capability properties.
- The DEBUG consumer becomes replace-not-add (once the SDK supports it). Today the Spring client adds it beside the
  customizers' consumers.
- Several client transport properties set with no `type` fail at startup (today websocket, then http, then
  stdio wins). It goes in the CHANGELOG with the extraction.
- The agent's `transport.http.port` and `max-concurrent-streams-per-connection` move under
  `transport.http.listener.*`.
- `spring.acp.agent.transport.type=websocket` becomes a synonym for `http`.
