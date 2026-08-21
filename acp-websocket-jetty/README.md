# acp-websocket-jetty

WebSocket transport for ACP agents using embedded Jetty. Allows agents to accept WebSocket connections from clients instead of using stdio.

## Key Classes

| Class | Purpose |
|-------|---------|
| `WebSocketAcpAgentTransport` | Embedded Jetty WebSocket server for agents |

## Installation

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-websocket-jetty</artifactId>
    <version>0.9.0</version>
</dependency>
```

## When to Use

Use WebSocket transport when the agent runs as a long-lived server process that multiple clients connect to over the network. For single-client CLI usage, stdio transport (`acp-core`) is simpler.

## Documentation

- [API Reference — Transports](https://lab.pollack.ai/docs/acp-java-sdk/reference/java#transports)
