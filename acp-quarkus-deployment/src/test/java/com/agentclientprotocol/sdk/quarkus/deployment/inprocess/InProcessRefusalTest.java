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

/** In process, without a permission handler bean, the client refuses the agent's request. */
class InProcessRefusalTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(ClaimsAgent.class, Chat.class))
		.overrideConfigKey("quarkus.acp.agent.transport.type", "in-process");

	@Inject
	AcpSyncClient client;

	@Inject
	Chat chat;

	@Test
	void withoutAPermissionHandlerTheRequestIsRefused() {
		String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/claims", List.of())).sessionId();
		client.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("review"))));

		assertThat(chat.messages).containsExactly("reviewing the claim", "refused");
	}

}
