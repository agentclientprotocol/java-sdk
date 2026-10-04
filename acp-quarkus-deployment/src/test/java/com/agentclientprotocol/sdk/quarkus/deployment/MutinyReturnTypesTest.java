/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Handlers return Mutiny types: a {@code Uni} of the response, and from {@code @Prompt}
 * a {@code Multi} that streams the reply (text, content blocks and session updates);
 * a {@code Multi} from any other handler is an error the client sees.
 */
class MutinyReturnTypesTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(MutinyAgent.class, InMemoryAgentTransport.class))
		.overrideConfigKey("quarkus.acp.agent.shutdown-on-transport-end", "false");

	@Inject
	InMemoryAgentTransport transport;

	@Test
	void uniAndMultiResultsAreAnswered() {
		AcpSyncClient client = transport.client();
		client.initialize();
		String sessionId = client.newSession(InMemoryAgentTransport.newSession()).sessionId();
		assertThat(sessionId).startsWith("uni-");

		AcpSchema.PromptResponse streamed = client.prompt(InMemoryAgentTransport.prompt(sessionId, "stream"));
		assertThat(streamed.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(transport.messages).containsExactly("one", "two", "three");

		assertThatThrownBy(() -> client.sendExtRequest("_quarkus/multi", Map.of()))
			.isInstanceOf(AcpError.class)
			// The handler's ReturnValueHandlingException is logged at the agent, not sent.
			.hasMessage("Internal error");
	}

	@AcpAgent
	public static class MutinyAgent {

		@NewSession
		Uni<AcpSchema.NewSessionResponse> newSession() {
			return Uni.createFrom()
				.item(() -> new AcpSchema.NewSessionResponse("uni-" + UUID.randomUUID(), null, null))
				.onItem()
				.delayIt()
				.by(Duration.ofMillis(10));
		}

		@Prompt
		Multi<Object> prompt(AcpSchema.PromptRequest request) {
			return Multi.createFrom().items("one", new AcpSchema.TextContent("two"),
					new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent("three")));
		}

		@ExtRequest("_quarkus/multi")
		Multi<String> notAPrompt() {
			return Multi.createFrom().items("never");
		}

	}

}
