/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpAgentAssembly;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpStdioAgentHost;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code quarkus.acp.agent.enabled=false} the {@code @AcpAgent} class stays an
 * ordinary bean and no agent is served (so nothing reads standard input).
 */
class AgentDisabledTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(QuietAgent.class))
		.overrideConfigKey("quarkus.acp.agent.enabled", "false");

	@Test
	void noAgentIsServed() {
		assertThat(Arc.container().instance(AcpStdioAgentHost.class).isAvailable()).isFalse();
		assertThat(Arc.container().instance(AcpAgentAssembly.class).isAvailable()).isFalse();
	}

	@AcpAgent
	public static class QuietAgent {

		@Prompt
		AcpSchema.PromptResponse prompt() {
			return AcpSchema.PromptResponse.endTurn();
		}

	}

}
