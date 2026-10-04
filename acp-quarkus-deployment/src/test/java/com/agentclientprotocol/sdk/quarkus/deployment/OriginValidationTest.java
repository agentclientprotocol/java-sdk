/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.net.URI;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.http.HttpProbes;
import io.quarkus.test.QuarkusUnitTest;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Quarkus endpoint applies the SDK's {@code Origin} rule on both of its routes: the servlet
 * (HTTP and SSE) and the Vert.x WebSocket route. A foreign origin is answered 403, a loopback or
 * listed one is served. Before, both served any origin.
 */
class OriginValidationTest {

	private static final String LISTED = "https://app.example.com";

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(OriginAgent.class))
		.overrideConfigKey("quarkus.acp.agent.transport.type", "http")
		.overrideConfigKey("quarkus.acp.agent.transport.http.allowed-origins", LISTED);

	@TestHTTPResource("/acp")
	URI endpoint;

	@Test
	void aForeignOriginIsRefusedOverHttpAndOnTheWebSocketHandshake() throws Exception {
		assertThat(HttpProbes.initialize(endpoint, "http://evil.example").statusCode()).isEqualTo(403);
		assertThat(HttpProbes.get(endpoint, "http://evil.example")).isEqualTo(403);
		assertThat(HttpProbes.webSocketHandshake(endpoint, "http://evil.example")).isEqualTo(403);
	}

	@Test
	void loopbackListedAndAbsentOriginsAreServed() throws Exception {
		for (String origin : new String[] { "http://localhost:3000", "http://127.0.0.1", LISTED, null }) {
			assertThat(HttpProbes.initialize(endpoint, origin).statusCode()).as(String.valueOf(origin)).isEqualTo(200);
			assertThat(HttpProbes.webSocketHandshake(endpoint, origin)).as(String.valueOf(origin))
				.isEqualTo(HttpProbes.SWITCHING_PROTOCOLS);
		}
	}

	@AcpAgent
	public static class OriginAgent {

		@Prompt
		AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
			return AcpSchema.PromptResponse.endTurn();
		}

	}

}
