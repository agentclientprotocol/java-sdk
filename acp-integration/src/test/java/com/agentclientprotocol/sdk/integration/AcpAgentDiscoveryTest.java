/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.util.List;

import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery.AgentCandidate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcpAgentDiscoveryTest {

	private static final AgentCandidate<String> FIRST = new AgentCandidate<>("first", String.class, () -> "a");

	private static final AgentCandidate<Integer> SECOND = new AgentCandidate<>("second", Integer.class, () -> 1);

	@Test
	void noneIsAClientOnlyApplication() {
		assertThat(AcpAgentDiscovery.requireSingle(List.of())).isEmpty();
		assertThat(AcpAgentDiscovery.requireSingle(List.<String>of(), null)).isEmpty();
	}

	@Test
	void oneIsServed() {
		assertThat(AcpAgentDiscovery.requireSingle(List.of(FIRST))).contains(FIRST);
		assertThat(AcpAgentDiscovery.requireSingle(List.of("com.example.Agent"), "acp.agent.enabled"))
			.contains("com.example.Agent");
	}

	@Test
	void moreThanOneIsAnErrorNamingThem() {
		assertThatThrownBy(() -> AcpAgentDiscovery.requireSingle(List.of(SECOND, FIRST)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Found 2 @AcpAgent beans [java.lang.Integer, java.lang.String], but an application serves "
					+ "one: remove @AcpAgent from all but one");
		assertThatThrownBy(() -> AcpAgentDiscovery.requireSingle(List.of(FIRST, SECOND), "acp.agent.enabled"))
			.hasMessageEndingWith(", or set acp.agent.enabled=false to serve none");
		assertThatThrownBy(() -> AcpAgentDiscovery.requireSingle(List.of("b.Second", "a.First"),
				"quarkus.acp.agent.enabled"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Found 2 @AcpAgent classes [a.First, b.Second], but an application serves one: remove "
					+ "@AcpAgent from all but one, or set quarkus.acp.agent.enabled=false to serve none");
	}

	@Test
	void aCandidateNeedsEveryPart() {
		assertThatThrownBy(() -> new AgentCandidate<>(null, String.class, () -> "")).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new AgentCandidate<>("n", null, () -> "")).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new AgentCandidate<>("n", String.class, null)).isInstanceOf(NullPointerException.class);
	}

}
