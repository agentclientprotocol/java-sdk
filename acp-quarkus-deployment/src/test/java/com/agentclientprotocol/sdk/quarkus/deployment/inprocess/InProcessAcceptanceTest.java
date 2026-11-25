/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment.inprocess;

import java.util.List;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order's acceptance test for Quarkus: an {@code @AcpAgent} class and
 * {@code quarkus.acp.agent.transport.type=in-process}, nothing else, gives the application an
 * initialized client of its own agent: a prompt streams updates to the application's update
 * handler, and its permission handler answers. After the application stops, the agent is closed
 * once.
 */
class InProcessAcceptanceTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(ClaimsAgent.class, Chat.class, Reviewer.class))
		.overrideConfigKey("quarkus.acp.agent.transport.type", "in-process")
		.setBeforeAllCustomizer(() -> System.clearProperty(ClaimsAgent.CLOSES))
		.setAfterAllCustomizer(() -> assertThat(Integer.getInteger(ClaimsAgent.CLOSES, 0)).isEqualTo(1));

	@Inject
	AcpSyncClient client;

	@Inject
	Chat chat;

	@Inject
	Reviewer reviewer;

	@Test
	void aPromptStreamsUpdatesAndTheApplicationsHandlerAnswersThePermissionRequest() {
		String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/claims", List.of())).sessionId();
		AcpSchema.PromptResponse response = client
			.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("review"))));

		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(chat.messages).containsExactly("reviewing the claim", "approved");
		assertThat(reviewer.asked).hasSize(1);
	}

}
