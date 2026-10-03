/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.it;

import java.util.List;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Quarkus client bean, configured from {@code quarkus.acp.client.transport.http.uri}
 * and customized by a bean, against the application's own agent over Streamable HTTP.
 */
@QuarkusTest
class ClientBeanTest {

	@Inject
	AcpSyncClient client;

	@Inject
	RecordingCustomizer customizer;

	@Test
	void injectedClientPromptsTheAgent() {
		assertThat(client.initialize().protocolVersion()).isEqualTo(AcpSchema.LATEST_PROTOCOL_VERSION);
		String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/tmp", List.of())).sessionId();
		AcpSchema.PromptResponse response = client
			.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("bean"))));
		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(customizer.messages).containsExactly("Hello from Quarkus, bean! (prompt 1)");
	}

}
