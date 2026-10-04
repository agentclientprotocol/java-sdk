# acp-spring-boot-starter

The Spring Boot 4.1 starter for the ACP Java SDK: depend on it to serve an `@AcpAgent` bean or to
get an ACP client from `spring.acp.client.*`. It brings `acp-spring-boot-autoconfigure`,
`acp-agent-support` and `spring-boot-starter`, and has no code of its own.

```xml
<dependency>
    <groupId>com.agentclientprotocol</groupId>
    <artifactId>acp-spring-boot-starter</artifactId>
    <version>${acp.version}</version>
</dependency>
```

The guide, with every `spring.acp.*` property, the transports and the limitations, is the
[`acp-spring-boot-autoconfigure` README](../acp-spring-boot-autoconfigure/README.md).
