/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A prompt turn cancelled end to end, client to agent and back, as ACP v1 describes it
 * (prompt turn, Cancellation): after {@code session/cancel} the agent may still send
 * {@code session/update}s, which the client still accepts, and then answers the original
 * {@code session/prompt} with stop reason {@code cancelled}; only once that turn has
 * completed may the client send another prompt.
 */
class PromptCancellationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String SESSION = "session-cancel";

	@Test
	void theTurnEndsWithTheCancelledPromptsResponseNotWithTheCancel() {
		Sinks.Empty<Void> cancelReceived = Sinks.empty();
		Sinks.Empty<Void> finishAborting = Sinks.empty();
		AtomicInteger prompts = new AtomicInteger();
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono
				.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse(SESSION, null, null)))
			.cancelHandler(notification -> Mono.fromRunnable(cancelReceived::tryEmitEmpty))
			.promptHandler((request, context) -> {
				if (prompts.incrementAndGet() > 1) {
					return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
				}
				// The first prompt runs until cancelled, sends a last update, and answers cancelled.
				return cancelReceived.asMono()
					.then(finishAborting.asMono())
					.then(context.sendUpdate(SESSION, new AcpSchema.AgentMessageChunk("agent_message_chunk",
							new AcpSchema.TextContent("aborted"))))
					.thenReturn(new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED));
			})
			.build();
		agent.start().block(TIMEOUT);

		List<String> updates = new CopyOnWriteArrayList<>();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> Mono.fromRunnable(() -> updates.add(
					((AcpSchema.TextContent) ((AcpSchema.AgentMessageChunk) notification.update()).content()).text())))
			.build();
		try {
			client.initialize().block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
			AcpSchema.PromptRequest prompt = new AcpSchema.PromptRequest(SESSION,
					List.of(new AcpSchema.TextContent("work")));
			Mono<AcpSchema.PromptResponse> cancelled = client.prompt(prompt).cache();
			cancelled.subscribe(response -> {
			}, error -> {
			});

			client.cancel(new AcpSchema.CancelNotification(SESSION)).block(TIMEOUT);
			cancelReceived.asMono().block(TIMEOUT);

			assertThatThrownBy(() -> client.prompt(prompt).block(TIMEOUT))
				.as("a prompt before the cancelled one has answered is rejected")
				.isInstanceOfSatisfying(AcpError.class,
						error -> assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INVALID_REQUEST));

			finishAborting.tryEmitEmpty();
			assertThat(cancelled.block(TIMEOUT).stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);
			assertThat(updates).as("the client accepts updates sent after its cancel").containsExactly("aborted");

			assertThat(client.prompt(prompt).block(TIMEOUT).stopReason())
				.as("once the cancelled turn has completed, the next prompt is accepted")
				.isEqualTo(AcpSchema.StopReason.END_TURN);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
			pair.closeGracefully().block(TIMEOUT);
		}
	}

	/**
	 * A handler that ignores the cancel: once the grace period passes the agent answers the
	 * prompt {@code cancelled} itself, after the update the handler had already sent.
	 */
	@Test
	void aHandlerThatIgnoresTheCancelIsAnsweredCancelledAfterItsUpdates() {
		Sinks.Empty<Void> updateSent = Sinks.empty();
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.cancelGracePeriod(Duration.ofMillis(200))
			.initializeHandler(request -> Mono
				.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse(SESSION, null, null)))
			.promptHandler((request, context) -> context
				.sendUpdate(SESSION,
						new AcpSchema.AgentMessageChunk("agent_message_chunk", new AcpSchema.TextContent("working")))
				.doOnSuccess(v -> updateSent.tryEmitEmpty())
				.then(Mono.never()))
			.build();
		agent.start().block(TIMEOUT);

		List<String> received = new CopyOnWriteArrayList<>();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> Mono.fromRunnable(() -> received.add("update")))
			.build();
		try {
			client.initialize().block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
			AcpSchema.PromptRequest prompt = new AcpSchema.PromptRequest(SESSION,
					List.of(new AcpSchema.TextContent("work")));
			Mono<AcpSchema.PromptResponse> ignored = client.prompt(prompt)
				.doOnNext(response -> received.add("answer " + response.stopReason()))
				.cache();
			ignored.subscribe(response -> {
			}, error -> {
			});
			updateSent.asMono().block(TIMEOUT);

			client.cancel(new AcpSchema.CancelNotification(SESSION)).block(TIMEOUT);

			assertThat(ignored.block(TIMEOUT).stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);
			assertThat(received).containsExactly("update", "answer cancelled");
			assertThatThrownBy(() -> client.prompt(prompt).block(Duration.ofMillis(300)))
				.as("the turn has ended: the next prompt is not rejected but runs (forever, in this handler)")
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Timeout on blocking read");
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
			pair.closeGracefully().block(TIMEOUT);
		}
	}

}
