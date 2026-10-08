# acp-annotations

Zero-dependency annotation library for declarative ACP agent development. Use with `acp-agent-support` for annotation processing at runtime.

## Annotations

| Annotation | Target | Purpose |
|-----------|--------|---------|
| `@AcpAgent` | Class | Marks a class as an ACP agent; its `name`, `version`, `title`, `authMethods`, `mcpHttp` and `mcpSse` are advertised in the `initialize` response |
| `@AuthMethod` | (inside `@AcpAgent`) | Declares one advertised authentication method, agent or terminal |
| `@Initialize` | Method | Optionally handles `initialize`; its response is laid over the one derived from the annotations |
| `@Authenticate` | Method | Handles `authenticate` |
| `@Logout` | Method | Handles `logout` |
| `@NewSession` | Method | Handles `session/new` |
| `@LoadSession` | Method | Handles `session/load` (with history replay) |
| `@ResumeSession` | Method | Handles `session/resume` (without history replay) |
| `@ListSessions` | Method | Handles `session/list` |
| `@CloseSession` | Method | Handles `session/close` |
| `@DeleteSession` | Method | Handles `session/delete` |
| `@ForkSession` | Method | Handles `session/fork` (unstable) |
| `@Prompt` | Method | Handles `session/prompt`; `image`, `audio` and `embeddedContext` advertise the prompt content accepted |
| `@SetSessionMode` | Method | Handles `session/set_mode` |
| `@SetSessionConfigOption` | Method | Handles `session/set_config_option` |
| `@ListProviders` | Method | Handles `providers/list` (unstable) |
| `@SetProvider` | Method | Handles `providers/set` (unstable) |
| `@DisableProvider` | Method | Handles `providers/disable` (unstable) |
| `@Cancel` | Method | Handles the `session/cancel` notification |
| `@ExtRequest` | Method | Handles a `_`-prefixed extension request |
| `@ExtNotification` | Method | Handles a `_`-prefixed extension notification |
| `@SessionId` | Parameter | Injects the current session ID |
| `@ConfigId` | Parameter | Injects the id of the config option being set |
| `@ConfigValue` | Parameter | Injects the config option's new value, typed `String`, `boolean` or `Object` |
| `@UnstableAcpApi` | Any | Marks API for protocol elements not in the stable schema |

## Installation

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-annotations</artifactId>
    <version>0.80.0</version>
</dependency>
```

## Documentation

- [Annotation-based Agent API](https://lab.pollack.ai/docs/acp-java-sdk/reference/java#annotation-based-api)
- [Tutorial](https://lab.pollack.ai/docs/acp-java-sdk/tutorial)
