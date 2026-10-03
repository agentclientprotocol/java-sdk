# ACP Agent Support Module

The `acp-agent-support` module provides an annotation-based programming model for building ACP agents. It eliminates boilerplate code while maintaining full compatibility with the builder-based API from `acp-core`.

## Quick Start

```java
@AcpAgent(name = "my-agent", version = "1.0.0")
class MyAgent {

    @NewSession
    NewSessionResponse newSession(NewSessionRequest req) {
        return new NewSessionResponse("session-" + UUID.randomUUID(), null, null);
    }

    @Prompt
    PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
        ctx.sendMessage("Processing your request...");
        return PromptResponse.text("Done!");
    }
}

// Bootstrap and run
AcpAgentSupport.create(new MyAgent())
    .transport(new StdioAcpAgentTransport())
    .run();
```

No `@Initialize` method is needed: the agent answers `initialize` with what its annotations declare
(see [Advertising Capabilities](#advertising-capabilities)).

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-agent-support</artifactId>
    <version>${acp.version}</version>
</dependency>
```

This module transitively includes `acp-annotations` and `acp-core`.

## Annotations

### Class-Level

| Annotation | Description |
|------------|-------------|
| `@AcpAgent` | Marks a class as an ACP agent. Required on the agent class or a superclass of it: handlers are discovered on the class and its superclasses, so a framework proxy of the bean (Spring CGLIB, Quarkus ArC, Micronaut AOP) is accepted and invoked through the proxy. Its attributes are advertised in the `initialize` response: `name`, `version` and `title` as `agentInfo` (the name defaults to the class's simple name, the version to the jar manifest's `Implementation-Version`, else `"unknown"`), `authMethods` (`@AuthMethod`s) as `authMethods`, and `mcpHttp`/`mcpSse` as `mcpCapabilities`. |
| `@AuthMethod` | One authentication method inside `@AcpAgent(authMethods = ...)`: `id`, `name`, `description`, and `type` `AGENT` (served by `@Authenticate`) or `TERMINAL` (the client reruns the agent program with `args` and `env`). |

### Handler Methods

| Annotation | JSON-RPC Method | Advertises | Description |
|------------|-----------------|------------|-------------|
| `@Initialize` | `initialize` | (the exchange itself) | Optional. Without one, the agent answers with the response derived from its annotations; with one, the method's response is laid over the derived one (see [Advertising Capabilities](#advertising-capabilities)). |
| `@Authenticate` | `authenticate` | `authMethods`, from `@AcpAgent(authMethods = ...)` | Authenticates with one of the agent methods the agent advertised. |
| `@Logout` | `logout` | `auth.logout` | Clears stored credentials. |
| `@NewSession` | `session/new` | nothing (baseline) | Creates a new agent session. Without one, the agent answers with a random session ID. |
| `@LoadSession` | `session/load` | `loadSession` | Loads an existing session by ID, replaying its history. |
| `@ResumeSession` | `session/resume` | `sessionCapabilities.resume` | Reconnects to an existing session without replaying history. |
| `@ListSessions` | `session/list` | `sessionCapabilities.list` | Lists sessions, optionally filtered by working directory. |
| `@CloseSession` | `session/close` | `sessionCapabilities.close` | Closes an active session. |
| `@DeleteSession` | `session/delete` | `sessionCapabilities.delete` | Permanently deletes a stored session. |
| `@ForkSession` | `session/fork` | `sessionCapabilities.fork` | Creates a session branched from an existing one (unstable, `@UnstableAcpApi`). |
| `@Prompt` | `session/prompt` | `promptCapabilities`, from its `image`, `audio` and `embeddedContext` attributes | Handles user prompts within a session. |
| `@SetSessionMode` | `session/set_mode` | nothing (modes are offered per session) | Changes the operational mode of a session. |
| `@SetSessionConfigOption` | `session/set_config_option` | nothing (options are offered per session) | Changes a session configuration option. |
| `@ListProviders` | `providers/list` | `providers` | Lists the providers the agent can route to (unstable). |
| `@SetProvider` | `providers/set` | `providers` | Configures a provider (unstable). |
| `@DisableProvider` | `providers/disable` | `providers` | Disables a provider (unstable). |
| `@Cancel` | `session/cancel` | nothing (baseline) | Handles cancellation notifications (fire-and-forget). The cancel does not end the prompt turn: the cancelled `@Prompt` method still returns, with stop reason `cancelled`, and the session rejects a new prompt until it has, or until the cancel grace period (default 60 s, `cancelGracePeriod`) passes and the agent answers `cancelled` itself. |
| `@ExtRequest("_name")` | any `_`-prefixed request | nothing (agreed outside the protocol) | Serves a custom extension request; the name must start with `_`. |
| `@ExtNotification("_name")` | any `_`-prefixed notification | nothing | Handles a custom extension notification; the name must start with `_`. |

A method without a handler for it is answered with `-32601` (Method not found); a notification
without one is ignored.

### Parameter Annotations

| Annotation | Description |
|------------|-------------|
| `@SessionId` | Injects the current session ID as a `String`, in handlers of session-scoped methods. |

## Advertising Capabilities

A client learns what an agent supports from the agent's `initialize` response, and only uses what
is advertised there: a client that is not told `loadSession` never calls `session/load`. An annotated
agent's response is derived from its class, so every handler it has is advertised:

```java
@AcpAgent(name = "notes-agent", version = "1.2.0", mcpHttp = true,
        authMethods = {
            @AuthMethod(id = "api-key", name = "API key", description = "Uses NOTES_API_KEY"),
            @AuthMethod(id = "login", name = "Log in", type = AuthMethod.Type.TERMINAL, args = "--login") })
class NotesAgent {

    @Authenticate AuthenticateResponse authenticate(AuthenticateRequest req) { ... }
    @Logout LogoutResponse logout() { ... }
    @LoadSession LoadSessionResponse load(LoadSessionRequest req) { ... }
    @ListSessions ListSessionsResponse list(ListSessionsRequest req) { ... }

    @Prompt(image = true, embeddedContext = true)
    PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) { ... }
}
```

answers `initialize` with

```json
{
  "protocolVersion": 1,
  "agentCapabilities": {
    "loadSession": true,
    "sessionCapabilities": { "list": {} },
    "mcpCapabilities": { "http": true, "sse": false },
    "promptCapabilities": { "audio": false, "embeddedContext": true, "image": true },
    "auth": { "logout": {} }
  },
  "authMethods": [
    { "id": "api-key", "name": "API key", "description": "Uses NOTES_API_KEY" },
    { "id": "login", "name": "Log in", "args": ["--login"], "type": "terminal" }
  ],
  "agentInfo": { "name": "notes-agent", "version": "1.2.0" }
}
```

- **Capabilities** come from the handler annotations, as the *Advertises* column of the
  [handler table](#handler-methods) lists. A test fails the build if a handler annotation has no
  declared mapping.
- **Prompt content and MCP transports** the agent accepts are declared, default false:
  `@Prompt(image, audio, embeddedContext)` and `@AcpAgent(mcpHttp, mcpSse)`.
- **Auth methods** are declared with `@AcpAgent(authMethods = @AuthMethod(...))`. An `AGENT` method
  needs an `@Authenticate` handler (the builder fails otherwise); a `TERMINAL` method is advertised
  only to a client that announced `clientCapabilities.auth.terminal`, as ACP requires.
- **`agentInfo`** is `@AcpAgent`'s `name`, `version` and `title`.
- **`protocolVersion`** is the client's when the SDK speaks it, otherwise the latest the SDK speaks.

**With an `@Initialize` method**, the derived response is the base and the method's response is laid
over it: a capability is advertised when either side advertises it (so `return InitializeResponse.ok()`
keeps every derived capability, and a handler's capability cannot be withdrawn: remove the handler);
the returned `authMethods` follow the derived ones, replacing any with the same id; the returned
`protocolVersion`, and the returned `agentInfo` and `_meta` when not null, win. Use it to read the
client's request, or to advertise what annotations cannot express, such as
`sessionCapabilities.additionalDirectories`.

## Handler Method Signatures

Handler methods support flexible signatures. The runtime automatically resolves parameters based on their types:

### Supported Parameter Types

| Parameter Type | Source |
|----------------|--------|
| The method's request type | The raw request or notification, in its handler: `InitializeRequest`, `AuthenticateRequest`, `LogoutRequest`, `NewSessionRequest`, `LoadSessionRequest`, `ResumeSessionRequest`, `ListSessionsRequest`, `CloseSessionRequest`, `DeleteSessionRequest`, `ForkSessionRequest`, `PromptRequest`, `SetSessionModeRequest`, `SetSessionConfigOptionRequest`, `ListProvidersRequest`, `SetProviderRequest`, `DisableProviderRequest`, `CancelNotification`. |
| Any type the JSON mapper can read | The params of an `@ExtRequest` or `@ExtNotification` (at most one such parameter). |
| `SyncPromptContext` | Synchronous context for sending messages, file I/O, permissions, etc. (in `@Prompt` handlers). |
| `PromptContext` | The async context the sync one wraps, for a `@Prompt` handler that composes `Mono`s. |
| `@SessionId String` | The current session ID. |
| `NegotiatedCapabilities` | The capabilities negotiated with the client on the request's connection (any handler). |
| `AcpSyncAgent` / `AcpAsyncAgent` | The agent serving the request's connection (any handler). |

Every handler, extension handlers included, can take `NegotiatedCapabilities`, `AcpSyncAgent` or
`AcpAsyncAgent`, resolved for the connection the request arrived on. The capabilities are what the
client offered in its `initialize` request, so they are available from the `@Initialize` handler
on. Under `buildFactory()`, one handler bean serves every connection, so a bean cannot keep "its"
agent in a field; take it as a parameter instead, for example to push changed config options
outside a prompt turn:

```java
@ExtNotification("_example.com/models_changed")
void modelsChanged(ModelsChanged event, AcpSyncAgent agent) {
    agent.sendSessionUpdate(event.sessionId(),
            new ConfigOptionUpdate(configOptions(event.models())));
}
```

### Example Handler Signatures

```java
@Initialize
InitializeResponse init() { ... }

@Initialize
InitializeResponse init(InitializeRequest req) { ... }

@Prompt
PromptResponse answer(PromptRequest req) { ... }

@Prompt
PromptResponse answer(PromptRequest req, SyncPromptContext ctx) { ... }

@Prompt
PromptResponse answer(SyncPromptContext ctx, @SessionId String sessionId) { ... }

@Prompt
String simpleAnswer(PromptRequest req) { ... }  // Converted to PromptResponse

@Prompt
void streamingAnswer(PromptRequest req, SyncPromptContext ctx) { ... }  // Returns endTurn()

@Cancel
void onCancel(CancelNotification notification) { ... }
```

## Return Value Handling

The runtime automatically converts return values to protocol response types:

| Return Type | Conversion |
|-------------|------------|
| The method's response type (`InitializeResponse`, `NewSessionResponse`, `PromptResponse`, ...) | Passed through directly. |
| `Mono` of the response type | Unwrapped (blocked on) and returned. |
| `String` (`@Prompt` only) | Converted to `PromptResponse.text(value)`. |
| `void` (`@Prompt` only) | Converted to `PromptResponse.endTurn()`. |
| `void` (`@Cancel`, `@ExtNotification`) | Notifications have no response. |
| Any value the JSON mapper can write (`@ExtRequest`) | Sent as the extension request's result. |

A request handler other than `@Prompt` that returns `void` or `null` (or an empty `Mono`) is
answered with `-32603` (Internal error): a request must get a result.

## Using SyncPromptContext

`SyncPromptContext` provides a rich API for agent-client interaction:

```java
@Prompt
PromptResponse handle(PromptRequest req, SyncPromptContext ctx) {
    // Get session info
    String sessionId = ctx.getSessionId();
    NegotiatedCapabilities caps = ctx.getClientCapabilities();

    // Send messages and thoughts
    ctx.sendMessage("Working on it...");
    ctx.sendThought("Let me analyze this...");

    // File operations (requires client capabilities)
    String content = ctx.readFile("/path/to/file.txt");
    ctx.writeFile("/path/to/output.txt", "content");

    // Optional file read (returns Optional)
    Optional<String> maybeContent = ctx.tryReadFile("/path/to/file.txt");

    // Ask for user permission
    boolean allowed = ctx.askPermission("Delete all files in /tmp?");

    // Multiple choice (empty if the user cancelled)
    Optional<String> choice = ctx.askChoice("Which format?", "JSON", "XML", "YAML");

    // Execute terminal commands (requires client capabilities)
    CommandResult result = ctx.execute("ls", "-la");
    if (result.success()) {
        ctx.sendMessage("Output: " + result.output());
    }

    return PromptResponse.endTurn();
}
```

### Cancellation

A prompt method learns that its prompt was cancelled from its context, whether by `session/cancel`
for its session or by `$/cancel_request` for its request; no `@Cancel` handler or shared state is
needed:

```java
@Prompt
PromptResponse handle(PromptRequest req, SyncPromptContext ctx) {
    for (Step step : plan(req)) {
        if (ctx.isCancelled()) {
            ctx.sendMessage("Stopped.");
            return PromptResponse.cancelled();
        }
        step.run(ctx);
    }
    return PromptResponse.endTurn();
}

@Prompt
Mono<PromptResponse> handle(PromptRequest req, PromptContext ctx) {
    return work(req, ctx)
        .takeUntilOther(ctx.whenCancelled())
        .defaultIfEmpty(PromptResponse.cancelled());
}
```

`SyncPromptContext.onCancel(Runnable)` runs an action once on cancel, for work that cannot poll
(a subprocess, an HTTP call). After `session/cancel` the method must answer `cancelled` within the
cancel grace period (`cancelGracePeriod`, default 60 s); once it passes the agent answers
`cancelled` itself and interrupts the method's thread. After `$/cancel_request` the agent has already
answered (`-32800`, or `cancelled` if the session was cancelled too), so the method just stops.

## Interceptors

Interceptors allow cross-cutting concerns like logging, metrics, or error handling:

```java
public class LoggingInterceptor implements AcpInterceptor {

    @Override
    public boolean preInvoke(AcpInvocationContext context) {
        log.info("Invoking: {}", context.getAcpMethod());
        return true;  // Continue processing
    }

    @Override
    public Object postInvoke(AcpInvocationContext context, Object result) {
        log.info("Result: {}", result);
        return result;
    }

    @Override
    public Object onError(AcpInvocationContext context, Throwable error) {
        log.error("Error in {}: {}", context.getAcpMethod(), error.getMessage());
        return null;  // Return null to re-throw, or return a replacement value
    }

    @Override
    public void afterCompletion(AcpInvocationContext context) {
        // Always called, even if exceptions occur
    }

    @Override
    public int getOrder() {
        return 0;  // Lower values execute first
    }
}

// Register interceptor
AcpAgentSupport.create(new MyAgent())
    .transport(transport)
    .interceptor(new LoggingInterceptor())
    .build();
```

## Custom Argument Resolvers

Extend argument resolution for custom parameter types:

```java
public class UserResolver implements ArgumentResolver {

    @Override
    public boolean supportsParameter(AcpMethodParameter parameter) {
        return parameter.getParameterType() == User.class;
    }

    @Override
    public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
        String sessionId = context.getSessionId().orElseThrow();
        return userService.findBySession(sessionId);
    }
}

// Register resolver
AcpAgentSupport.create(new MyAgent())
    .transport(transport)
    .argumentResolver(new UserResolver())
    .build();
```

## Custom Return Value Handlers

Handle custom return types:

```java
public class CompletableFutureHandler implements ReturnValueHandler {

    @Override
    public boolean supportsReturnType(AcpMethodParameter returnType) {
        return CompletableFuture.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object handleReturnValue(Object returnValue, AcpMethodParameter returnType,
            AcpInvocationContext context) {
        CompletableFuture<?> future = (CompletableFuture<?>) returnValue;
        return future.join();  // Block and return result
    }
}

// Register handler
AcpAgentSupport.create(new MyAgent())
    .transport(transport)
    .returnValueHandler(new CompletableFutureHandler())
    .build();
```

## Transport Configuration

### Stdio Transport (Default for CLI Agents)

```java
AcpAgentSupport.create(new MyAgent())
    .transport(new StdioAcpAgentTransport())
    .run();
```

### Remote Transport (Streamable HTTP and WebSocket)

`StreamableHttpAcpAgentTransport` (module `acp-streamable-http-jetty`) serves the Streamable HTTP
profile and the WebSocket upgrade on one path and creates one agent per connection, which
`buildFactory()` provides:

```java
AcpAgentFactory agents = AcpAgentSupport.create(new MyAgent()).buildFactory();

var server = new StreamableHttpAcpAgentTransport(8080, AcpJsonMapper.createDefault(), agents);
server.start().block();  // http://localhost:8080/acp and ws://localhost:8080/acp
```

Every connection's agent invokes the same `MyAgent` instance, the way every request to a Spring
controller reaches one bean: its handler methods run concurrently across connections and sessions,
so they must be thread-safe, with per-session state keyed by session id. Port `0` listens on an
ephemeral port; `server.getPort()` returns it once started.

### InMemory Transport (For Testing)

```java
InMemoryTransportPair pair = InMemoryTransportPair.create();

AcpAgentSupport support = AcpAgentSupport.create(new MyAgent())
    .transport(pair.agentTransport())
    .build();

support.start();

// Create client using the paired transport
AcpAsyncClient client = AcpClient.async(pair.clientTransport()).build();
```

## Builder API Reference

```java
AcpAgentSupport.create(agentInstance)      // Start with agent instance
    .transport(transport)                   // Required: set transport
    .requestTimeout(Duration.ofSeconds(60)) // Optional: request timeout (default: 30s)
    .cancelGracePeriod(Duration.ofSeconds(10)) // Optional: time a cancelled prompt has to return (default: 60s)
    .maxPromptDuration(Duration.ofMinutes(5))  // Optional: longest a prompt may run (default: none)
    .interceptor(interceptor)               // Optional: add interceptor
    .argumentResolver(resolver)             // Optional: add custom resolver
    .returnValueHandler(handler)            // Optional: add custom handler
    .build();                               // Build the support instance
```

A builder can be built more than once: `build()` and `buildFactory()` add the default resolvers
and return value handlers after the custom ones at build time without changing the builder, and
later builder changes do not affect what was already built.

### Alternative Creation Methods

```java
// From class (must have no-arg constructor)
AcpAgentSupport.create(MyAgent.class)

// With factory supplier
AcpAgentSupport.builder().agent(MyAgent.class, () -> new MyAgent(dependency))
```

Either way the agent is instantiated once, when it is registered, and that instance serves
every request, so state kept in its fields is shared across handlers.

### Running the Agent

```java
AcpAgentSupport support = AcpAgentSupport.create(new MyAgent())
    .transport(transport)
    .build();

// Option 1: Non-blocking start
support.start();
// ... do other work ...
support.close();

// Option 2: Blocking run (blocks until closed)
support.run();
```

## Complete Example

```java
@AcpAgent(name = "code-assistant", version = "1.0.0")
class CodeAssistant {

    private final Map<String, List<String>> sessionHistory = new ConcurrentHashMap<>();

    @NewSession
    NewSessionResponse newSession(NewSessionRequest req) {
        String sessionId = UUID.randomUUID().toString();
        sessionHistory.put(sessionId, new ArrayList<>());
        return new NewSessionResponse(sessionId, null, null);
    }

    @LoadSession
    LoadSessionResponse loadSession(LoadSessionRequest req) {
        if (!sessionHistory.containsKey(req.sessionId())) {
            throw new AcpProtocolException(AcpErrorCodes.RESOURCE_NOT_FOUND,
                "Session not found: " + req.sessionId());
        }
        return new LoadSessionResponse(null, null);
    }

    @Prompt
    PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
        String sessionId = ctx.getSessionId();
        sessionHistory.get(sessionId).add(extractText(req));

        ctx.sendThought("Analyzing the code...");

        // Check if we can read files (capabilities are null before initialize)
        NegotiatedCapabilities caps = ctx.getClientCapabilities();
        if (caps != null && caps.supportsReadTextFile()) {
            ctx.sendMessage("I can access files if needed.");
        }

        ctx.sendMessage("Here's my analysis...");
        return PromptResponse.endTurn();
    }

    @SetSessionMode
    SetSessionModeResponse setMode(SetSessionModeRequest req) {
        return new SetSessionModeResponse();
    }

    @Cancel
    void onCancel(CancelNotification notification, @SessionId String sessionId) {
        // Clean up any long-running operations
        log.info("Cancelled session: {}", sessionId);
    }

    private String extractText(PromptRequest req) {
        return req.prompt().stream()
            .filter(c -> c instanceof TextContent)
            .map(c -> ((TextContent) c).text())
            .collect(Collectors.joining("\n"));
    }
}

public class Main {
    public static void main(String[] args) {
        AcpAgentSupport.create(new CodeAssistant())
            .transport(new StdioAcpAgentTransport())
            .interceptor(new MetricsInterceptor())
            .run();
    }
}
```

## Migration from Builder API

The annotation-based API provides the same functionality as the builder API with less boilerplate:

### Builder API (Before)

```java
AcpAgent.sync(transport)
    .initializeHandler(req -> InitializeResponse.ok())
    .newSessionHandler(req -> new NewSessionResponse("session-1", null, null))
    .promptHandler((req, ctx) -> {
        ctx.sendMessage("Hello!");
        return PromptResponse.endTurn();
    })
    .build()
    .run();
```

### Annotation API (After)

```java
@AcpAgent(name = "my-agent", version = "1.0")
class MyAgent {
    @NewSession NewSessionResponse newSession() { return new NewSessionResponse("session-1", null, null); }
    @Prompt PromptResponse prompt(SyncPromptContext ctx) {
        ctx.sendMessage("Hello!");
        return PromptResponse.endTurn();
    }
}

AcpAgentSupport.create(new MyAgent()).transport(transport).run();
```

Both approaches produce the same runtime behavior and can coexist in the same application. The
annotated agent needs no initialize handler: it advertises what its annotations declare, where a
builder agent's initialize handler must build the response itself.

## Architecture

```
acp-agent-support
├── AcpAgentSupport          # Bootstrap and builder
├── AcpHandlerMethod         # Method + bean encapsulation
├── invocation/              # Invocation model shared by the extension points
│   ├── AcpMethodParameter   # Parameter metadata
│   └── AcpInvocationContext # Request context during invocation
├── resolver/                # Argument resolvers
│   ├── ArgumentResolver     # Interface
│   ├── ArgumentResolverComposite
│   ├── PromptRequestResolver
│   ├── PromptContextResolver
│   ├── SessionIdResolver
│   └── ...
├── handler/                 # Return value handlers
│   ├── ReturnValueHandler   # Interface
│   ├── ReturnValueHandlerComposite
│   ├── DirectResponseHandler
│   ├── StringToPromptResponseHandler
│   ├── VoidHandler
│   ├── MonoHandler
│   └── ExtensionResultHandler
└── interceptor/             # Interceptor chain
    ├── AcpInterceptor       # Interface
    └── InterceptorChain     # Execution chain
```

`AcpAgentSupport` uses `resolver`, `handler` and `interceptor`; those use only `invocation`, never
`AcpAgentSupport`, so there are no package cycles. The module uses acp-core's public API only.

## Dependencies

- `acp-annotations` - Zero-dependency annotation definitions
- `acp-core` - Core SDK with transport, schema, and client/agent APIs
- SLF4J - Logging facade
- Project Reactor - Reactive streams (from acp-core)
