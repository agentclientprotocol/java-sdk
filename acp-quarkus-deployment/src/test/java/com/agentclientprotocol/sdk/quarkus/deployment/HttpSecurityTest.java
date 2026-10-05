/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

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
 * Quarkus's own HTTP security protects {@code /acp}, over HTTP and on the WebSocket handshake,
 * because the endpoint is a route on the Quarkus router behind its authentication and
 * permission checks: an unauthenticated request is refused, an authenticated one served.
 */
class HttpSecurityTest {

	private static final Map<String, String> ALICE = Map.of("Authorization",
			"Basic " + Base64.getEncoder().encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8)));

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest().withApplicationRoot(jar -> jar.addClasses(SecuredAgent.class))
		.overrideConfigKey("quarkus.acp.agent.transport.type", "http")
		.overrideConfigKey("quarkus.http.auth.basic", "true")
		.overrideConfigKey("quarkus.security.users.embedded.enabled", "true")
		.overrideConfigKey("quarkus.security.users.embedded.plain-text", "true")
		.overrideConfigKey("quarkus.security.users.embedded.users.alice", "secret")
		.overrideConfigKey("quarkus.http.auth.permission.acp.paths", "/acp")
		.overrideConfigKey("quarkus.http.auth.permission.acp.policy", "authenticated");

	@TestHTTPResource("/acp")
	URI endpoint;

	@Test
	void anUnauthenticatedRequestIsRefusedOverHttpAndOnTheHandshake() throws Exception {
		assertThat(HttpProbes.initialize(endpoint, null).statusCode()).isEqualTo(401);
		assertThat(HttpProbes.webSocketHandshake(endpoint, null)).isEqualTo(401);
	}

	@Test
	void anAuthenticatedRequestIsServedOverHttpAndOnTheHandshake() throws Exception {
		assertThat(HttpProbes.initialize(endpoint, null, ALICE).statusCode()).isEqualTo(200);
		assertThat(HttpProbes.webSocketHandshake(endpoint, null, ALICE)).isEqualTo(HttpProbes.SWITCHING_PROTOCOLS);
	}

	@AcpAgent
	public static class SecuredAgent {

		@Prompt
		AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
			return AcpSchema.PromptResponse.endTurn();
		}

	}

}
