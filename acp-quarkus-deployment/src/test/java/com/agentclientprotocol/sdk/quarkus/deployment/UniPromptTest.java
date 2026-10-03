/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code Uni<String>} from {@code @Prompt} is read as the {@code String} the handler
 * could have returned directly: the text is sent to the client and the turn ends.
 */
class UniPromptTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(UniAgent.class, InMemoryAgentTransport.class))
		.overrideConfigKey("quarkus.acp.agent.shutdown-on-transport-end", "false");

	@Inject
	InMemoryAgentTransport transport;

	@Test
	void uniStringIsTheReply() {
		AcpSyncClient client = transport.client();
		client.initialize();
		String sessionId = client.newSession(InMemoryAgentTransport.newSession()).sessionId();
		AcpSchema.PromptResponse response = client.prompt(InMemoryAgentTransport.prompt(sessionId, "hi"));
		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(transport.messages).containsExactly("a Uni reply to hi");
	}

	@AcpAgent
	public static class UniAgent {

		@Prompt
		Uni<String> prompt(AcpSchema.PromptRequest request) {
			String text = ((AcpSchema.TextContent) request.prompt().get(0)).text();
			return Uni.createFrom().item("a Uni reply to " + text);
		}

	}

}
