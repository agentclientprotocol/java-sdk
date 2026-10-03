/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** A second {@code @AcpAgent} class fails the build, naming both. */
class TwoAgentsFailTheBuildTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(FirstAgent.class, SecondAgent.class))
		.assertException(error -> {
			Throwable root = error;
			while (root.getCause() != null && !(root instanceof IllegalStateException)) {
				root = root.getCause();
			}
			assertThat(root).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Found 2 @AcpAgent classes")
				.hasMessageContaining("FirstAgent")
				.hasMessageContaining("SecondAgent")
				.hasMessageContaining("quarkus.acp.agent.enabled=false");
		});

	@Test
	void buildFails() {
		fail("The build should have failed");
	}

	@AcpAgent
	public static class FirstAgent {

		@Prompt
		AcpSchema.PromptResponse prompt() {
			return AcpSchema.PromptResponse.endTurn();
		}

	}

	@AcpAgent
	public static class SecondAgent {

		@Prompt
		AcpSchema.PromptResponse prompt() {
			return AcpSchema.PromptResponse.endTurn();
		}

	}

}
