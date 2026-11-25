/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.inprocess;

import java.time.Duration;
import java.util.List;

import com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order's acceptance test for Spring Boot: an {@code @AcpAgent} class and
 * {@code spring.acp.agent.transport.type=in-process}, nothing else, gives the application an
 * initialized client of its own agent: a prompt streams updates to the application's update
 * handler, its permission handler answers, and the context closes cleanly, closing the agent.
 */
class InProcessAcceptanceTests {

	@BeforeEach
	void resetCloses() {
		ClaimsAgent.CLOSES.set(0);
	}

	@Test
	void aPromptStreamsUpdatesAndTheApplicationsHandlerAnswersThePermissionRequest() {
		ConfigurableApplicationContext context = start();
		InMemoryTransportPair pair = context.getBean(InMemoryTransportPair.class);
		try {
			InProcessApplication.Chat chat = context.getBean(InProcessApplication.Chat.class);
			InProcessApplication.Reviewer reviewer = context.getBean(InProcessApplication.Reviewer.class);

			AcpSchema.PromptResponse response = prompt(context.getBean(AcpSyncClient.class));

			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(chat.messages).containsExactly("reviewing the claim", "approved");
			assertThat(reviewer.asked).hasSize(1);
		}
		finally {
			context.close();
		}
		assertThat(context.isActive()).isFalse();
		assertThat(ClaimsAgent.CLOSES).hasValue(1);
		pair.agentTransport().awaitTermination().block(Duration.ofSeconds(5));
	}

	@Test
	void withoutAPermissionHandlerTheRequestIsRefused() {
		try (ConfigurableApplicationContext context = start("test.reviewer=false")) {
			assertThat(context.getBeansOfType(InProcessApplication.Reviewer.class)).isEmpty();

			prompt(context.getBean(AcpSyncClient.class));

			assertThat(context.getBean(InProcessApplication.Chat.class).messages).containsExactly("reviewing the claim",
					"refused");
		}
	}

	@Test
	void aClientTransportOfAnotherAgentIsRefused() {
		assertThatThrownBy(() -> start("spring.acp.client.transport.stdio.command=some-agent"))
			.hasStackTraceContaining("spring.acp.agent.transport.type=in-process connects the application's client")
			.hasStackTraceContaining("remove spring.acp.client.transport.*");
	}

	private static AcpSchema.PromptResponse prompt(AcpSyncClient client) {
		String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/claims", List.of())).sessionId();
		return client.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("review"))));
	}

	private static ConfigurableApplicationContext start(String... properties) {
		return new SpringApplicationBuilder(InProcessApplication.class).web(WebApplicationType.NONE)
			.properties("spring.acp.agent.transport.type=in-process", "spring.main.banner-mode=off")
			.properties(properties)
			.run();
	}

}
