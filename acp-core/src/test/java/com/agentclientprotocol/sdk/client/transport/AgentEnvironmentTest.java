/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent process's environment: by default the client's whole environment plus
 * {@link AgentParameters#getEnv()}; with {@code inheritEnvironment(false)} only the safe default
 * variables and the ones added on the builder, so the client's secrets do not reach the agent.
 */
class AgentEnvironmentTest {

	private static final Set<String> SAFE = Set.of("HOME", "LOGNAME", "PATH", "SHELL", "TERM", "USER", "APPDATA",
			"HOMEDRIVE", "HOMEPATH", "LOCALAPPDATA", "PROCESSOR_ARCHITECTURE", "SYSTEMDRIVE", "SYSTEMROOT", "TEMP",
			"USERNAME", "USERPROFILE");

	/** Starts {@code java -version} and returns the environment it was started with. */
	private static Map<String, String> environmentOf(AgentParameters params) {
		ProcessBuilder[] used = new ProcessBuilder[1];
		StdioAcpClientTransport transport = new StdioAcpClientTransport(params) {
			@Override
			protected ProcessBuilder getProcessBuilder() {
				used[0] = super.getProcessBuilder();
				return used[0];
			}
		};
		try {
			transport.connect(message -> Mono.empty()).block(Duration.ofSeconds(30));
			return Map.copyOf(used[0].environment());
		}
		finally {
			transport.close();
		}
	}

	private static String java() {
		return Path.of(System.getProperty("java.home"), "bin", "java").toString();
	}

	private static String aVariableOutsideTheSafeList() {
		List<String> candidates = System.getenv().keySet().stream().filter(name -> !SAFE.contains(name)).toList();
		assertThat(candidates).as("the test JVM has a variable outside the safe list").isNotEmpty();
		return candidates.get(0);
	}

	@Test
	void byDefaultTheAgentInheritsTheClientsWholeEnvironment() {
		String inherited = aVariableOutsideTheSafeList();
		Map<String, String> env = environmentOf(
				AgentParameters.builder(java()).arg("-version").addEnvVar("ACP_ADDED", "1").build());

		assertThat(env).containsKey(inherited).containsEntry("ACP_ADDED", "1");
	}

	@Test
	void withoutInheritingTheAgentGetsOnlyTheSafeListAndWhatWasAdded() {
		String notInherited = aVariableOutsideTheSafeList();
		AgentParameters params = AgentParameters.builder(java())
			.arg("-version")
			.addEnvVar("ACP_ADDED", "1")
			.inheritEnvironment(false)
			.build();
		assertThat(params.isInheritEnvironment()).isFalse();

		Map<String, String> env = environmentOf(params);

		assertThat(env).doesNotContainKey(notInherited).containsEntry("ACP_ADDED", "1");
		assertThat(env.keySet()).allMatch(name -> SAFE.contains(name) || name.equals("ACP_ADDED"));
	}

}
