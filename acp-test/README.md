# acp-test

Test utilities for unit testing ACP clients and agents without subprocesses or network connections.

## Key Classes

| Class | Purpose |
|-------|---------|
| `InMemoryTransportPair` | Creates connected client/agent transports in-process |
| `MockAcpAgent` | Records requests, provides configurable responses |
| `MockAcpClient` | Records session updates, handles agent requests |

## Installation

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-test</artifactId>
    <version>0.9.0</version>
    <scope>test</scope>
</dependency>
```

## Usage

```java
var pair = InMemoryTransportPair.create();

// Build client with one side
AcpSyncClient client = AcpClient.sync(pair.clientTransport()).build();

// Build agent with the other
AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
    .initializeHandler(req -> InitializeResponse.ok())
    .promptHandler((req, ctx) -> { /* ... */ })
    .build();
```

## Documentation

- [Module 16: In-Memory Testing](https://lab.pollack.ai/docs/acp-java-sdk/tutorial/16-in-memory-testing)
