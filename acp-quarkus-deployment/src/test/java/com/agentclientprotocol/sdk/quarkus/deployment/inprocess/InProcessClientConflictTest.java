/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment.inprocess;

import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/** In process, a client transport of another agent fails the startup, naming both settings. */
class InProcessClientConflictTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(ClaimsAgent.class))
		.overrideConfigKey("quarkus.acp.agent.transport.type", "in-process")
		.overrideConfigKey("quarkus.acp.client.transport.stdio.command", "some-agent")
		.assertException(error -> assertThat(error)
			.hasStackTraceContaining("quarkus.acp.agent.transport.type=in-process connects the application's client")
			.hasStackTraceContaining("remove quarkus.acp.client.transport.*"));

	@Test
	void theStartupFails() {
		// assertException checks the failure
	}

}
