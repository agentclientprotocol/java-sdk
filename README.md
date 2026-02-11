# ACP Java SDK

> **Documentation**: https://springaicommunity.mintlify.app/acp-java-sdk

Pure Java implementation of the [Agent Client Protocol (ACP)](https://agentclientprotocol.com/) specification for building both clients and agents.

## Overview

The Agent Client Protocol (ACP) standardizes communication between code editors and coding agents. This SDK provides two modules:

- **Client** — connect to and interact with ACP-compliant agents
- **Agent** — build ACP-compliant agents in Java

Three API styles for building agents:

| Style | Best for | Example |
|-------|----------|---------|
| [**Annotation-based**](#2-hello-world-agent-annotation-based) | Least boilerplate — `@AcpAgent`, `@Prompt` annotations | [Jump to example](#2-hello-world-agent-annotation-based) |
| [**Sync**](#3-hello-world-agent-sync) | Simple blocking handlers with plain return values | [Jump to example](#3-hello-world-agent-sync) |
| [**Async**](#4-hello-world-agent-async) | Reactive applications using Project Reactor `Mono` | [Jump to example](#4-hello-world-agent-async) |

**Key Features:**
- Java 17+, type-safe, stdio and WebSocket transports
- Capability negotiation and structured error handling
- For a hands-on walkthrough, see the **[ACP Java Tutorial](https://github.com/markpollack/acp-java-tutorial)**

## Installation

Published to Maven Central:

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-core</artifactId>
    <version>0.9.0</version>
</dependency>
```

For annotation-based agent development:
```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-agent-support</artifactId>
    <version>0.9.0</version>
</dependency>
```

For WebSocket server support (agents accepting WebSocket connections):
```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-websocket-jetty</artifactId>
    <version>0.9.0</version>
</dependency>
```

---

## Getting Started

### 1. Hello World Client

Connect to an ACP agent and send a prompt ([tutorial](https://github.com/markpollack/acp-java-tutorial/tree/main/module-01-first-contact)):

```java
import com.agentclientprotocol.sdk.client.*;
import com.agentclientprotocol.sdk.client.transport.*;
import com.agentclientprotocol.sdk.spec.AcpSchema.*;
import java.util.List;

// Connect to an agent via stdio
var params = AgentParameters.builder("gemini").arg("--experimental-acp").build();
var transport = new StdioAcpClientTransport(params);

// Create client
AcpSyncClient client = AcpClient.sync(transport).build();

// Initialize, create session, send prompt
client.initialize();
var session = client.newSession(new NewSessionRequest("/workspace", List.of()));
var response = client.prompt(new PromptRequest(
    session.sessionId(),
    List.of(new TextContent("Hello, world!"))
));

client.close();
```

### 2. Hello World Agent (Annotation-Based)

The simplest way to build an agent — use annotations ([tutorial](https://github.com/markpollack/acp-java-tutorial/tree/main/module-12-echo-agent)):

```java
import com.agentclientprotocol.sdk.annotation.*;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.spec.AcpSchema.*;

@AcpAgent
class HelloAgent {

    @Initialize
    InitializeResponse init() {
        return InitializeResponse.ok();
    }

    @NewSession
    NewSessionResponse newSession() {
        return new NewSessionResponse(UUID.randomUUID().toString(), null, null);
    }

    @Prompt
    PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
        ctx.sendMessage("Hello from the agent!");
        return PromptResponse.endTurn();
    }
}

// Bootstrap and run
AcpAgentSupport.create(new HelloAgent())
    .transport(new StdioAcpAgentTransport())
    .run();
```

### 3. Hello World Agent (Sync)

The builder API with blocking handlers and plain return values ([tutorial](https://github.com/markpollack/acp-java-tutorial/tree/main/module-13-agent-handlers)):

```java
import com.agentclientprotocol.sdk.agent.*;
import com.agentclientprotocol.sdk.agent.transport.*;
import com.agentclientprotocol.sdk.spec.AcpSchema.*;
import java.util.List;
import java.util.UUID;

// Create stdio transport
var transport = new StdioAcpAgentTransport();

// Build sync agent - handlers use plain return values (no Mono!)
AcpSyncAgent agent = AcpAgent.sync(transport)
    .initializeHandler(req ->
        new InitializeResponse(1, new AgentCapabilities(), List.of()))
    .newSessionHandler(req ->
        new NewSessionResponse(UUID.randomUUID().toString(), null, null))
    .promptHandler((req, context) -> {
        // Send updates using blocking void method
        context.sendUpdate(req.sessionId(),
            new AgentMessageChunk("agent_message_chunk",
                new TextContent("Hello from the agent!")));
        // Return response directly (no Mono!)
        return new PromptResponse(StopReason.END_TURN);
    })
    .build();

// Run agent (blocks until client disconnects)
agent.run();
```

### 4. Hello World Agent (Async)

For reactive applications, use the async API with Project Reactor ([tutorial](https://github.com/markpollack/acp-java-tutorial/tree/main/module-22-async-agent)):

```java
import com.agentclientprotocol.sdk.agent.*;
import com.agentclientprotocol.sdk.agent.transport.*;
import com.agentclientprotocol.sdk.spec.AcpSchema.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.UUID;

var transport = new StdioAcpAgentTransport();

AcpAsyncAgent agent = AcpAgent.async(transport)
    .initializeHandler(req -> Mono.just(
        new InitializeResponse(1, new AgentCapabilities(), List.of())))
    .newSessionHandler(req -> Mono.just(
        new NewSessionResponse(UUID.randomUUID().toString(), null, null)))
    .promptHandler((req, context) ->
        context.sendUpdate(req.sessionId(),
                new AgentMessageChunk("agent_message_chunk",
                    new TextContent("Hello from the agent!")))
            .then(Mono.just(new PromptResponse(StopReason.END_TURN))))
    .build();

// Start and await termination
agent.start().then(agent.awaitTermination()).block();
```

---

## Progressive Examples

### 5. Streaming Updates

Send real-time updates to the client during prompt processing (tutorial: [client-side](https://github.com/markpollack/acp-java-tutorial/tree/main/module-05-streaming-updates), [agent-side](https://github.com/markpollack/acp-java-tutorial/tree/main/module-14-sending-updates)).

**Annotation-based:**
```java
@Prompt
PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
    ctx.sendUpdate(req.sessionId(),
        new AgentThoughtChunk("agent_thought_chunk", new TextContent("Thinking...")));
    ctx.sendMessage("Here's my response.");
    return PromptResponse.endTurn();
}
```

**Sync:**
```java
.promptHandler((req, context) -> {
    context.sendUpdate(req.sessionId(),
        new AgentThoughtChunk("agent_thought_chunk",
            new TextContent("Thinking...")));
    context.sendUpdate(req.sessionId(),
        new AgentMessageChunk("agent_message_chunk",
            new TextContent("Here's my response.")));
    return new PromptResponse(StopReason.END_TURN);
})
```

**Async:**
```java
.promptHandler((request, context) -> {
    return context.sendUpdate(request.sessionId(),
            new AgentThoughtChunk("agent_thought_chunk",
                new TextContent("Thinking...")))
        .then(context.sendUpdate(request.sessionId(),
            new AgentMessageChunk("agent_message_chunk",
                new TextContent("Here's my response."))))
        .then(Mono.just(new PromptResponse(StopReason.END_TURN)));
})
```

**Client - receiving updates:**
```java
AcpSyncClient client = AcpClient.sync(transport)
    .sessionUpdateConsumer(notification -> {
        var update = notification.update();
        if (update instanceof AgentMessageChunk msg) {
            System.out.print(((TextContent) msg.content()).text());
        }
    })
    .build();
```

### 6. Agent-to-Client Requests

Agents can request file operations from the client ([tutorial](https://github.com/markpollack/acp-java-tutorial/tree/main/module-15-agent-requests)). The `context` parameter provides access to all agent capabilities.

**Agent (Sync) - reading files:**
```java
AcpSyncAgent agent = AcpAgent.sync(transport)
    .promptHandler((req, context) -> {
        // Read a file from the client's filesystem
        var fileResponse = context.readTextFile(
            new ReadTextFileRequest(req.sessionId(), "pom.xml", null, 10));
        String content = fileResponse.content();

        // Write a file
        context.writeTextFile(
            new WriteTextFileRequest(req.sessionId(), "output.txt", "Hello!"));

        return new PromptResponse(StopReason.END_TURN);
    })
    .build();

agent.run();
```

**Client - registering file handlers:**
```java
AcpSyncClient client = AcpClient.sync(transport)
    .readTextFileHandler((ReadTextFileRequest req) -> {
        // Handlers receive typed requests directly
        String content = Files.readString(Path.of(req.path()));
        return new ReadTextFileResponse(content);
    })
    .writeTextFileHandler((WriteTextFileRequest req) -> {
        Files.writeString(Path.of(req.path()), req.content());
        return new WriteTextFileResponse();
    })
    .build();
```

### 7. Capability Negotiation

Check what features the peer supports before using them ([tutorial](https://github.com/markpollack/acp-java-tutorial/tree/main/module-17-capability-negotiation)):

```java
// Client: check agent capabilities after initialize
client.initialize(new InitializeRequest(1, clientCaps));

NegotiatedCapabilities agentCaps = client.getAgentCapabilities();
if (agentCaps.supportsLoadSession()) {
    // Agent supports session persistence
}
if (agentCaps.supportsImageContent()) {
    // Agent can handle image content in prompts
}
```

```java
// Agent: check client capabilities before requesting operations
NegotiatedCapabilities clientCaps = agent.getClientCapabilities();
if (clientCaps.supportsReadTextFile()) {
    agent.readTextFile(...);
} else {
    // Client doesn't support file reading - handle gracefully
}

// Or use require methods (throws AcpCapabilityException if not supported)
clientCaps.requireWriteTextFile();
agent.writeTextFile(...);
```

### 8. Error Handling

Handle protocol errors with structured exceptions ([tutorial](https://github.com/markpollack/acp-java-tutorial/tree/main/module-11-error-handling)):

```java
import com.agentclientprotocol.sdk.error.*;

try {
    client.prompt(request);
} catch (AcpProtocolException e) {
    if (e.isConcurrentPrompt()) {
        // Another prompt is already in progress
    } else if (e.isMethodNotFound()) {
        // Agent doesn't support this method
    }
    System.err.println("Error " + e.getCode() + ": " + e.getMessage());
} catch (AcpCapabilityException e) {
    // Tried to use a capability the peer doesn't support
    System.err.println("Capability not supported: " + e.getCapability());
} catch (AcpConnectionException e) {
    // Transport-level connection error
}
```

### 9. WebSocket Transport

Use WebSocket instead of stdio for network-based communication:

**Client (JDK-native, no extra dependencies):**
```java
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import java.net.URI;

var transport = new WebSocketAcpClientTransport(
    URI.create("ws://localhost:8080/acp"),
    McpJsonMapper.getDefault()
);
AcpSyncClient client = AcpClient.sync(transport).build();
```

**Agent (requires acp-websocket-jetty module):**
```java
import com.agentclientprotocol.sdk.agent.transport.WebSocketAcpAgentTransport;

var transport = new WebSocketAcpAgentTransport(
    8080,                           // port
    "/acp",                         // path
    McpJsonMapper.getDefault()
);
AcpAsyncAgent agent = AcpAgent.async(transport)
    // ... handlers ...
    .build();

agent.start().block();  // Starts WebSocket server on port 8080
```

---

## API Reference

### Packages

| Package | Description |
|---------|-------------|
| `com.agentclientprotocol.sdk.spec` | Protocol types (`AcpSchema.*`) |
| `com.agentclientprotocol.sdk.client` | Client SDK (`AcpClient`, `AcpAsyncClient`, `AcpSyncClient`) |
| `com.agentclientprotocol.sdk.agent` | Agent SDK (`AcpAgent`, `AcpAsyncAgent`, `AcpSyncAgent`) |
| `com.agentclientprotocol.sdk.agent.support` | Annotation-based agent runtime (`AcpAgentSupport`) |
| `com.agentclientprotocol.sdk.annotation` | Agent annotations (`@AcpAgent`, `@Prompt`, etc.) |
| `com.agentclientprotocol.sdk.capabilities` | Capability negotiation (`NegotiatedCapabilities`) |
| `com.agentclientprotocol.sdk.error` | Exceptions (`AcpProtocolException`, `AcpCapabilityException`) |

### Maven Artifacts

| Artifact | Description |
|----------|-------------|
| [`acp-core`](https://central.sonatype.com/artifact/com.agentclientprotocol/acp-core) | Client and Agent SDKs, stdio and WebSocket client transports |
| [`acp-annotations`](https://central.sonatype.com/artifact/com.agentclientprotocol/acp-annotations) | `@AcpAgent`, `@Prompt`, and other annotations |
| [`acp-agent-support`](https://central.sonatype.com/artifact/com.agentclientprotocol/acp-agent-support) | Annotation-based agent runtime |
| [`acp-test`](https://central.sonatype.com/artifact/com.agentclientprotocol/acp-test) | In-memory transport and mock utilities for testing |
| [`acp-websocket-jetty`](https://central.sonatype.com/artifact/com.agentclientprotocol/acp-websocket-jetty) | Jetty-based WebSocket server transport for agents |

### Transports

| Transport | Client | Agent | Module |
|-----------|--------|-------|--------|
| Stdio | `StdioAcpClientTransport` | `StdioAcpAgentTransport` | acp-core |
| WebSocket | `WebSocketAcpClientTransport` | `WebSocketAcpAgentTransport` | acp-core / acp-websocket-jetty |

---

## Building

```bash
./mvnw compile      # Compile
./mvnw test         # Run unit tests (258 tests)
./mvnw verify       # Run unit tests + integration tests
./mvnw install      # Install to local Maven repository
```

### Integration Tests

Integration tests connect to real ACP agents and require additional setup:

```bash
# Gemini CLI integration tests (requires API key and gemini CLI)
export GEMINI_API_KEY=your_key_here
./mvnw verify -pl acp-core
```

**Test Categories:**
| Type | Command | Count | Requirements |
|------|---------|-------|--------------|
| Unit tests | `./mvnw test` | 258 | None |
| Clean shutdown IT | `./mvnw verify` | 4 | None |
| Gemini CLI IT | `./mvnw verify` | 5 | `GEMINI_API_KEY`, `gemini` CLI in PATH |

## Testing Your Code

Use the mock utilities for testing:

```java
import com.agentclientprotocol.sdk.test.*;

// Create in-memory transport pair for testing
InMemoryTransportPair pair = InMemoryTransportPair.create();

// Use pair.clientTransport() for client, pair.agentTransport() for agent
MockAcpClient mockClient = MockAcpClient.builder(pair.clientTransport())
    .fileContent("/test.txt", "test content")
    .build();
```

---

## Tutorial

For a hands-on, progressive introduction to the SDK, see the **[ACP Java Tutorial](https://github.com/markpollack/acp-java-tutorial)** -- 30 modules covering client basics, agent development, streaming, testing, and IDE integration.

## Roadmap

### v0.9.0 (Current)
- Client and Agent SDKs with async/sync APIs
- Stdio and WebSocket transports
- Capability negotiation
- Structured error handling
- Full protocol compliance (all SessionUpdate types, MCP configs, `_meta` extensibility)
- Snapshot builds published to Maven Central
- 258 tests

### v1.0.0 (Planned)
- Stable release to Maven Central
- Production hardening
- Performance optimizations
