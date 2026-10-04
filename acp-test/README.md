# acp-test

Test utilities for ACP clients and agents: they run a client and an agent in one JVM, with no
process and no network.

| Class | Purpose |
|-------|---------|
| `InMemoryTransportPair` | Two connected transports: build a client on `clientTransport()` and an agent on `agentTransport()` |
| `MockAcpAgent` | A scripted agent for testing client code. It answers `initialize`, `session/new` and `session/prompt` and records every request |
| `MockAcpClient` | A scripted client for testing agent code. It answers permission and file requests, records them and every session update, and drives the agent with blocking calls |

## Installation

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-test</artifactId>
    <version>${acp.version}</version>
    <scope>test</scope>
</dependency>
```

`acp-test` brings `acp-core` and `acp-json-jackson2`.

## A real client and agent

```java
InMemoryTransportPair pair = InMemoryTransportPair.create();

AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
    .initializeHandler(request -> AcpSchema.InitializeResponse.ok())
    .newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1"))
    .promptHandler((request, context) -> {
        context.sendMessage("Hello");
        return AcpSchema.PromptResponse.endTurn();
    })
    .build();
agent.start();

AcpSyncClient client = AcpClient.sync(pair.clientTransport()).build();
client.initialize();
String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
    .sessionId();
AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest(sessionId,
    List.of(new AcpSchema.TextContent("Hi"))));

client.close();
agent.close();
```

Each pair carries one connection, so use a new pair for every client and agent. Messages pass
between the two as Java objects and are never written as JSON. To test what goes over the wire,
use a real transport.

## Testing a client: `MockAcpAgent`

```java
InMemoryTransportPair pair = InMemoryTransportPair.create();
MockAcpAgent agent = MockAcpAgent.builder(pair.agentTransport())
    .promptResponse(request -> AcpSchema.PromptResponse.refusal())
    .build();
agent.start();

AcpSyncClient client = AcpClient.sync(pair.clientTransport()).build();
client.initialize();
String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
    .sessionId();                                       // "mock-session"
client.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("Delete everything"))));

List<AcpSchema.PromptRequest> prompts = agent.getReceivedPrompts();
client.close();
agent.close();
```

Without builder settings it answers `initialize` with protocol version 1 and no capabilities,
`session/new` with session ID `mock-session`, and every prompt with stop reason `end_turn`. Any
other request gets `-32601` (method not found). `sendSessionUpdate` sends updates to the client, and
`async()` returns the wrapped agent for other calls to the client. To wait for prompts, call
`expectPrompts(n)` before the client sends them, then `awaitPrompts(timeout)`.

## Testing an agent: `MockAcpClient`

```java
InMemoryTransportPair pair = InMemoryTransportPair.create();
AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
    .initializeHandler(request -> AcpSchema.InitializeResponse.ok())
    .newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1"))
    .promptHandler((request, context) -> {
        String source = context.readFile("/src/Main.java");
        context.sendMessage("Main.java has " + source.length() + " characters");
        return AcpSchema.PromptResponse.endTurn();
    })
    .build();
agent.start();

MockAcpClient client = MockAcpClient.builder(pair.clientTransport())
    .fileContent("/src/Main.java", "class Main {}")
    .build();                                           // connects; no start()
client.initialize();
client.newSession("/workspace");
AcpSchema.PromptResponse response = client.prompt("Count the characters");

List<AcpSchema.SessionNotification> updates = client.getReceivedUpdates();
List<AcpSchema.ReadTextFileRequest> reads = client.getReceivedFileReadRequests();
client.close();
agent.close();
```

The mock client advertises file reading and writing and nothing else. Without builder settings it
answers every permission request with option ID `allow`, whatever options the agent offered,
and every file read with `// Mock file content`, and it writes no file. Its blocking calls, prompts included, wait at most the
builder's `requestTimeout`, 10 seconds by default. When `prompt` returns, the turn's session updates
have all arrived. To wait for updates sent between prompts, call `expectUpdates(n)` before they are
sent, then `awaitUpdates(timeout)`.

The Javadoc of each class has the details. For a walkthrough, see
[Module 16: In-Memory Testing](https://github.com/markpollack/acp-java-tutorial/tree/main/module-16-in-memory-testing)
of the ACP Java Tutorial.
