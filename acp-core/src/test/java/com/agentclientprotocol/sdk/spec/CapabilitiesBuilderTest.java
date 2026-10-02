/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AcpSchema.ClientCapabilities#builder()} and
 * {@link AcpSchema.AgentCapabilities#builder()} reach every field without positional
 * {@code null}s, and start from the defaults of the no-argument constructors.
 */
class CapabilitiesBuilderTest {

	@Test
	void emptyClientBuilderIsTheDefaultCapabilities() {
		assertThat(AcpSchema.ClientCapabilities.builder().build()).isEqualTo(new AcpSchema.ClientCapabilities());
	}

	@Test
	void clientBuilderAdvertisesBooleanConfigOptions() {
		var caps = AcpSchema.ClientCapabilities.builder()
			.session(AcpSchema.ClientSessionCapabilities.withBooleanConfigOptions())
			.build();

		assertThat(caps).isEqualTo(new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(), false,
				new AcpSchema.ClientSessionCapabilities(AcpSchema.SessionConfigOptionsCapabilities.withBoolean()), null,
				null, null));
	}

	@Test
	void clientBuilderReachesEveryField() {
		var fs = new AcpSchema.FileSystemCapability(true, true);
		var session = AcpSchema.ClientSessionCapabilities.withBooleanConfigOptions();
		var auth = new AcpSchema.AuthCapabilities(true);
		var elicitation = AcpSchema.ElicitationCapabilities.formOnly();

		var caps = AcpSchema.ClientCapabilities.builder()
			.fs(fs)
			.terminal(true)
			.session(session)
			.auth(auth)
			.elicitation(elicitation)
			.meta(Map.of("k", "v"))
			.build();

		assertThat(caps)
			.isEqualTo(new AcpSchema.ClientCapabilities(fs, true, session, auth, elicitation, Map.of("k", "v")));
	}

	@Test
	void emptyAgentBuilderIsTheDefaultCapabilities() {
		assertThat(AcpSchema.AgentCapabilities.builder().build()).isEqualTo(new AcpSchema.AgentCapabilities());
	}

	@Test
	void agentBuilderReachesEveryField() {
		var session = new AcpSchema.SessionCapabilities(Map.of(), Map.of(), null);
		var mcp = new AcpSchema.McpCapabilities(true, false);
		var prompt = new AcpSchema.PromptCapabilities(true, true, true);
		var auth = AcpSchema.AgentAuthCapabilities.withLogout();
		var providers = new AcpSchema.ProvidersCapabilities();

		var caps = AcpSchema.AgentCapabilities.builder()
			.loadSession(true)
			.sessionCapabilities(session)
			.mcpCapabilities(mcp)
			.promptCapabilities(prompt)
			.auth(auth)
			.providers(providers)
			.meta(Map.of("k", "v"))
			.build();

		assertThat(caps).isEqualTo(
				new AcpSchema.AgentCapabilities(true, session, mcp, prompt, auth, providers, Map.of("k", "v")));
	}

}
