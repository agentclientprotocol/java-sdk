/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code $/cancel_request} end to end, both directions: the side that gives up on a request
 * cancels the work at its peer.
 */
class CancelRequestEndToEndTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String SESSION = "session-cancel-request";

	/** The client disposes its prompt: the agent's handler is cancelled and the turn ends. */
	@Test
	void aPromptTheClientGivesUpOnIsCancelledAtTheAgent() {
		Sinks.Empty<Void> handlerCancelled = Sinks.empty();
		Sinks.Empty<Void> handlerStarted = Sinks.empty();
		AtomicInteger prompts = new AtomicInteger();
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono
				.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse(SESSION, null, null)))
			.promptHandler((request, context) -> {
				if (prompts.incrementAndGet() > 1) {
					return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
				}
				return Mono.<AcpSchema.PromptResponse>never()
					.doOnSubscribe(s -> handlerStarted.tryEmitEmpty())
					.doOnCancel(handlerCancelled::tryEmitEmpty);
			})
			.build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize().block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
			AcpSchema.PromptRequest prompt = new AcpSchema.PromptRequest(SESSION,
					List.of(new AcpSchema.TextContent("work")));

			Disposable first = client.prompt(prompt).subscribe();
			handlerStarted.asMono().block(TIMEOUT);
			first.dispose();

			handlerCancelled.asMono().block(TIMEOUT);
			// The agent answered the cancelled prompt and ended its turn: the next one is accepted.
			assertThat(retryWhileConcurrent(client, prompt).stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
			pair.closeGracefully().block(TIMEOUT);
		}
	}

	/**
	 * The cancelled answer and the next prompt travel separately; the client does not see the
	 * answer (it gave up), so it may get there first. Retry briefly on -32000.
	 */
	private static AcpSchema.PromptResponse retryWhileConcurrent(AcpAsyncClient client,
			AcpSchema.PromptRequest prompt) {
		return client.prompt(prompt)
			.retryWhen(reactor.util.retry.Retry.fixedDelay(50, Duration.ofMillis(20)))
			.block(TIMEOUT);
	}

	/**
	 * The agent gives up on a permission request (its own timeout here): the client's
	 * permission handler is cancelled.
	 */
	@Test
	void aPermissionRequestTheAgentGivesUpOnIsCancelledAtTheClient() {
		Sinks.Empty<Void> permissionCancelled = Sinks.empty();
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSchema.ToolCallUpdate toolCall = new AcpSchema.ToolCallUpdate("tool-1", "Write File",
				AcpSchema.ToolKind.EDIT, AcpSchema.ToolCallStatus.PENDING, null, null, null, null);
		List<AcpSchema.PermissionOption> options = List
			.of(new AcpSchema.PermissionOption("allow", "Allow", AcpSchema.PermissionOptionKind.ALLOW_ONCE));
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono
				.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse(SESSION, null, null)))
			.promptHandler((request, context) -> context
				.requestPermission(new AcpSchema.RequestPermissionRequest(SESSION, toolCall, options))
				.timeout(Duration.ofMillis(200))
				.map(response -> new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN))
				.onErrorReturn(new AcpSchema.PromptResponse(AcpSchema.StopReason.REFUSAL)))
			.build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.requestPermissionHandler(request -> Mono.<AcpSchema.RequestPermissionResponse>never()
				.doOnCancel(permissionCancelled::tryEmitEmpty))
			.build();
		try {
			client.initialize().block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			AcpSchema.PromptResponse response = client
				.prompt(new AcpSchema.PromptRequest(SESSION, List.of(new AcpSchema.TextContent("work"))))
				.block(TIMEOUT);

			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.REFUSAL);
			permissionCancelled.asMono().block(TIMEOUT);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
			pair.closeGracefully().block(TIMEOUT);
		}
	}

}
