/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An agent sends no terminal method to a client that did not advertise {@code terminal}: each
 * of the five fails locally with {@link AcpCapabilityException}, from the agent and from a
 * prompt's context.
 *
 * <p>
 * ACP spec 7628b153 (agentclientprotocol/agent-client-protocol), docs/protocol/v1/terminals.mdx:26,
 * "If terminal is false or not present, the Agent MUST NOT attempt to call any terminal
 * methods."
 * </p>
 */
class TerminalCapabilityTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String SESSION = "s1";

	private static final String TERMINAL = "t-from-elsewhere";

	// ACP spec 7628b153: terminals.mdx:26, "the Agent MUST NOT attempt to call any terminal methods"
	@Test
	void theAgentRefusesEveryTerminalMethodWithoutTheCapability() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.promptHandler((request, context) -> Mono.just(PromptResponse.endTurn()))
			.build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize().block(TIMEOUT);

			for (Function<AcpAsyncAgent, Mono<?>> call : terminalCalls()) {
				assertThatThrownBy(() -> call.apply(agent).block(TIMEOUT)).isInstanceOf(AcpCapabilityException.class)
					.hasMessageContaining("terminal");
			}
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
		}
	}

	// ACP spec 7628b153: terminals.mdx:26, "the Agent MUST NOT attempt to call any terminal methods"
	@Test
	void aPromptContextRefusesEveryTerminalMethodWithoutTheCapability() {
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport()).promptHandler((request, context) -> {
			List<Runnable> calls = List.of(
					() -> context.createTerminal(new AcpSchema.CreateTerminalRequest(SESSION, "ls", List.of(), null,
							List.of(), null)),
					() -> context.getTerminalOutput(new AcpSchema.TerminalOutputRequest(SESSION, TERMINAL)),
					() -> context.waitForTerminalExit(new AcpSchema.WaitForTerminalExitRequest(SESSION, TERMINAL)),
					() -> context.killTerminal(new AcpSchema.KillTerminalCommandRequest(SESSION, TERMINAL)),
					() -> context.releaseTerminal(new AcpSchema.ReleaseTerminalRequest(SESSION, TERMINAL)));
			for (Runnable call : calls) {
				try {
					call.run();
				}
				catch (Throwable failure) {
					failures.add(failure);
				}
			}
			return PromptResponse.endTurn();
		}).build();
		agent.start();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize().block(TIMEOUT);
			client.prompt(new AcpSchema.PromptRequest(SESSION, List.of(new AcpSchema.TextContent("go"))))
				.block(TIMEOUT);

			assertThat(failures).hasSize(5).allSatisfy(failure -> assertThat(failure)
				.isInstanceOf(AcpCapabilityException.class)
				.hasMessageContaining("terminal"));
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully();
		}
	}

	private static List<Function<AcpAsyncAgent, Mono<?>>> terminalCalls() {
		return List.of(
				a -> a.createTerminal(
						new AcpSchema.CreateTerminalRequest(SESSION, "ls", List.of(), null, List.of(), null)),
				a -> a.getTerminalOutput(new AcpSchema.TerminalOutputRequest(SESSION, TERMINAL)),
				a -> a.waitForTerminalExit(new AcpSchema.WaitForTerminalExitRequest(SESSION, TERMINAL)),
				a -> a.killTerminal(new AcpSchema.KillTerminalCommandRequest(SESSION, TERMINAL)),
				a -> a.releaseTerminal(new AcpSchema.ReleaseTerminalRequest(SESSION, TERMINAL)));
	}

}
