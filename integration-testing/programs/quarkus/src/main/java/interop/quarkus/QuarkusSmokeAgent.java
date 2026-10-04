/*
 * Copyright 2025-2026 the original author or authors.
 */

package interop.quarkus;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import interop.framework.SmokeAgent;

/**
 * The shared smoke agent on Quarkus: {@code @AcpAgent} alone makes it a bean, which acp-quarkus
 * finds at build time and serves over stdio or HTTP and WebSocket, as the build chose.
 */
@AcpAgent(name = "interop-quarkus-agent", version = "1")
public class QuarkusSmokeAgent extends SmokeAgent {

}
