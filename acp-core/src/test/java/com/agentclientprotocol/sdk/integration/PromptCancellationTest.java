/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.PromptContext;
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
					.then(context.sendSessionUpdate(new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent("aborted"))))
					.thenReturn(new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED));
			})
			.build();
		agent.start().block(TIMEOUT);

		List<String> updates = new CopyOnWriteArrayList<>();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateHandler(notification -> Mono.fromRunnable(() -> updates.add(
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
				.sendSessionUpdate(new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent("working")))
				.doOnSuccess(v -> updateSent.tryEmitEmpty())
				.then(Mono.never()))
			.build();
		agent.start().block(TIMEOUT);

		List<String> received = new CopyOnWriteArrayList<>();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateHandler(notification -> Mono.fromRunnable(() -> received.add("update")))
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

	/**
	 * ACP: after {@code session/cancel} the agent may send updates, but "MUST ensure that it does
	 * so before responding to the session/prompt request". A handler that keeps sending after
	 * the grace period passed and the agent answered {@code cancelled} has its updates dropped.
	 */
	@Test
	void updatesFromAHandlerStillRunningAfterTheForcedAnswerAreDropped() throws Exception {
		Sinks.Empty<Void> firstUpdateSent = Sinks.empty();
		java.util.concurrent.atomic.AtomicReference<PromptContext> contextRef = new java.util.concurrent.atomic.AtomicReference<>();
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.cancelGracePeriod(Duration.ofMillis(200))
			.initializeHandler(request -> Mono
				.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse(SESSION, null, null)))
			.promptHandler((request, context) -> {
				contextRef.set(context);
				return context.sendMessage("before")
					.doOnSuccess(v -> firstUpdateSent.tryEmitEmpty())
					.then(Mono.never());
			})
			.build();
		agent.start().block(TIMEOUT);

		java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
		List<String> afterAnswer = new CopyOnWriteArrayList<>();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateHandler(notification -> Mono.fromRunnable(() -> {
				if (answered.get()) {
					afterAnswer.add(notification.update().toString());
				}
			}))
			.build();
		try {
			client.initialize().block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
			Mono<AcpSchema.PromptResponse> prompt = client.prompt(AcpSchema.PromptRequest.text(SESSION, "work"))
				.doOnNext(response -> answered.set(true))
				.cache();
			prompt.subscribe(response -> {
			}, error -> {
			});
			firstUpdateSent.asMono().block(TIMEOUT);
			client.cancel(new AcpSchema.CancelNotification(SESSION)).block(TIMEOUT);
			assertThat(prompt.block(TIMEOUT).stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);

			// The handler, still running, keeps sending through its prompt context.
			PromptContext context = contextRef.get();
			context.sendMessage("late message").block(TIMEOUT);
			context.sendSessionUpdate(new AcpSchema.AgentThoughtChunk(new AcpSchema.TextContent("late thought")))
				.block(TIMEOUT);
			// Anything sent arrives before the answer to a later request on the same connection.
			client.sendExtRequest("_x/probe", java.util.Map.of()).onErrorResume(e -> Mono.empty()).block(TIMEOUT);

			assertThat(afterAnswer).isEmpty();
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully().block(TIMEOUT);
			pair.closeGracefully().block(TIMEOUT);
		}
	}

	/** The same for a sync handler's context, and once the handler answered the prompt itself. */
	@Test
	void updatesThroughASyncContextAfterThePromptWasAnsweredAreDropped() throws Exception {
		java.util.concurrent.atomic.AtomicReference<SyncPromptContext> contextRef = new java.util.concurrent.atomic.AtomicReference<>();
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse(SESSION, null, null))
			.promptHandler((request, context) -> {
				contextRef.set(context);
				context.sendMessage("during");
				return AcpSchema.PromptResponse.endTurn();
			})
			.build();
		agent.start();

		java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
		List<String> seen = new CopyOnWriteArrayList<>();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateHandler(notification -> Mono.fromRunnable(
					() -> seen.add((answered.get() ? "after " : "before ") + notification.update().getClass().getSimpleName())))
			.build();
		try {
			client.initialize().block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
			client.prompt(AcpSchema.PromptRequest.text(SESSION, "work")).doOnNext(r -> answered.set(true)).block(TIMEOUT);

			contextRef.get().sendMessage("after the answer");
			client.sendExtRequest("_x/probe", java.util.Map.of()).onErrorResume(e -> Mono.empty()).block(TIMEOUT);

			assertThat(seen).containsExactly("before AgentMessageChunk");
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.close();
			pair.closeGracefully().block(TIMEOUT);
		}
	}

}
