/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client stops a prompt turn with a {@link CancellationSignal} passed to {@code prompt}, found by
 * completion on the client, instead of a Reactor context entry: cancelling it sends
 * {@code session/cancel} for the prompt's session, and the prompt still returns the agent's answer,
 * stop reason {@code cancelled}.
 */
class PromptCancellationSignalTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final InMemoryTransportPair pair = InMemoryTransportPair.create();

	private final CountDownLatch started = new CountDownLatch(1);

	private final AtomicInteger cancels = new AtomicInteger();

	private AcpAsyncAgent agent;

	@BeforeEach
	void startAgent() {
		agent = AcpAgent.async(pair.agentTransport())
			.promptHandler((request, context) -> context.sendMessage("working")
				.doOnSuccess(v -> started.countDown())
				.then(context.whenCancelled())
				.thenReturn(AcpSchema.PromptResponse.cancelled()))
			.cancelHandler(notification -> Mono.fromRunnable(cancels::incrementAndGet))
			.build();
		agent.start().block(TIMEOUT);
	}

	@AfterEach
	void stop() {
		agent.close();
		pair.closeGracefully().block(TIMEOUT);
	}

	private static AcpSchema.PromptRequest request(String sessionId) {
		return new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("go")));
	}

	@Test
	void asyncPromptStopsWhenTheSignalIsCancelled() throws Exception {
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize().block(TIMEOUT);
			String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/w", List.of()))
				.block(TIMEOUT)
				.sessionId();
			CancellationSignal stop = new CancellationSignal();
			CompletableFuture<AcpSchema.PromptResponse> answer = client.prompt(request(sessionId), stop).toFuture();
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(answer).isNotDone();

			stop.cancel();

			assertThat(answer.get(5, TimeUnit.SECONDS).stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);
			assertThat(stop.isCancelled()).isTrue();
			assertThat(cancels.get()).isEqualTo(1);
		}
		finally {
			client.close();
		}
	}

	@Test
	void syncPromptStopsWhenAnotherThreadCancelsTheSignal() throws Exception {
		AcpSyncClient client = AcpClient.sync(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize();
			String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/w", List.of())).sessionId();
			CancellationSignal stop = new CancellationSignal();
			Thread stopper = new Thread(() -> {
				try {
					if (started.await(5, TimeUnit.SECONDS)) {
						stop.cancel();
					}
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			});
			stopper.start();

			AcpSchema.PromptResponse response = client.prompt(request(sessionId), stop);

			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);
			stopper.join(5000);
		}
		finally {
			client.close();
		}
	}

	@Test
	void aSignalNotCancelledSendsNoCancel() {
		InMemoryTransportPair other = InMemoryTransportPair.create();
		AtomicInteger otherCancels = new AtomicInteger();
		AcpAsyncAgent endTurn = AcpAgent.async(other.agentTransport())
			.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn()))
			.cancelHandler(notification -> Mono.fromRunnable(otherCancels::incrementAndGet))
			.build();
		endTurn.start().block(TIMEOUT);
		AcpSyncClient client = AcpClient.sync(other.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize();
			String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/w", List.of())).sessionId();
			CancellationSignal stop = new CancellationSignal();

			assertThat(client.prompt(request(sessionId), stop).stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			stop.cancel();
			client.newSession(new AcpSchema.NewSessionRequest("/w", List.of()));

			assertThat(otherCancels.get()).isZero();
		}
		finally {
			client.close();
			endTurn.close();
			other.closeGracefully().block(TIMEOUT);
		}
	}

}
