/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A prompt handler learns that its prompt was cancelled from its context: by
 * {@code session/cancel} for its session, or by {@code $/cancel_request} for its request. The
 * grace period is long here, so a {@code cancelled} answer that arrives promptly is the
 * handler's own (it carries the handler's {@code _meta}).
 */
class PromptContextCancellationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final Duration LONG_GRACE = Duration.ofSeconds(30);

	private static final String SESSION = "session-signal";

	private static final Map<String, Object> HANDLERS_OWN = Map.of("answeredBy", "handler");

	private static final AcpSchema.PromptRequest PROMPT = new AcpSchema.PromptRequest(SESSION,
			List.of(new AcpSchema.TextContent("work")));

	@Test
	void aSyncHandlerPollingIsCancelledAnswersCancelledOnSessionCancel() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.cancelGracePeriod(LONG_GRACE)
			.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse(SESSION, null, null))
			.promptHandler((request, context) -> {
				while (!context.isCancelled()) {
					sleep(10);
				}
				return new PromptResponse(AcpSchema.StopReason.CANCELLED, HANDLERS_OWN);
			})
			.build();
		agent.start();
		AcpAsyncClient client = client(pair);
		try {
			Mono<PromptResponse> response = startPrompt(client);
			client.cancel(new AcpSchema.CancelNotification(SESSION)).block(TIMEOUT);

			PromptResponse answer = response.block(TIMEOUT);
			assertThat(answer.stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);
			assertThat(answer.meta()).isEqualTo(HANDLERS_OWN);
		}
		finally {
			close(client, pair);
			agent.closeGracefully();
		}
	}

	@Test
	void anAsyncHandlerAnswersWhenCancelledCompletes() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.cancelGracePeriod(LONG_GRACE)
			.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse(SESSION, null, null)))
			.promptHandler((request, context) -> context.whenCancelled()
				.then(Mono.fromSupplier(() -> new PromptResponse(AcpSchema.StopReason.CANCELLED,
						context.isCancelled() ? HANDLERS_OWN : Map.of()))))
			.build();
		agent.start().block(TIMEOUT);
		AcpAsyncClient client = client(pair);
		try {
			Mono<PromptResponse> response = startPrompt(client);
			client.cancel(new AcpSchema.CancelNotification(SESSION)).block(TIMEOUT);

			assertThat(response.block(TIMEOUT).meta()).isEqualTo(HANDLERS_OWN);
		}
		finally {
			close(client, pair);
			agent.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void aCancelRequestForThePromptSignalsItsHandler() throws Exception {
		CountDownLatch running = new CountDownLatch(1);
		CountDownLatch sawCancel = new CountDownLatch(1);
		CountDownLatch callbackRan = new CountDownLatch(1);
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.cancelGracePeriod(LONG_GRACE)
			.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse(SESSION, null, null))
			.promptHandler((request, context) -> {
				context.onCancel(callbackRan::countDown);
				running.countDown();
				while (!context.isCancelled()) {
					// A sync handler is interrupted when its request is cancelled: keep polling
					Thread.interrupted();
					sleep(10);
				}
				sawCancel.countDown();
				return PromptResponse.cancelled();
			})
			.build();
		agent.start();
		AcpAsyncClient client = client(pair);
		try {
			client.initialize().block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/w", List.of())).block(TIMEOUT);
			Disposable prompt = client.prompt(PROMPT).subscribe(response -> {
			}, error -> {
			});
			assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

			prompt.dispose(); // sends $/cancel_request for the prompt

			assertThat(sawCancel.await(5, TimeUnit.SECONDS)).as("isCancelled() turned true").isTrue();
			assertThat(callbackRan.await(5, TimeUnit.SECONDS)).as("onCancel callback ran").isTrue();
		}
		finally {
			close(client, pair);
			agent.closeGracefully();
		}
	}

	@Test
	void anUncancelledPromptIsNotCancelledAndACallbackRegisteredAfterACancelRunsAtOnce() {
		AtomicBoolean cancelledAtEnd = new AtomicBoolean(true);
		AtomicBoolean lateCallbackRan = new AtomicBoolean();
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.cancelGracePeriod(LONG_GRACE)
			.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse(SESSION, null, null))
			.promptHandler((request, context) -> {
				String text = ((AcpSchema.TextContent) request.prompt().get(0)).text();
				if (text.equals("work")) {
					cancelledAtEnd.set(context.isCancelled());
					return PromptResponse.endTurn();
				}
				while (!context.isCancelled()) {
					sleep(10);
				}
				context.onCancel(() -> lateCallbackRan.set(true));
				return PromptResponse.cancelled();
			})
			.build();
		agent.start();
		AcpAsyncClient client = client(pair);
		try {
			assertThat(startPrompt(client).block(TIMEOUT).stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(cancelledAtEnd).isFalse();

			Mono<PromptResponse> second = client
				.prompt(new AcpSchema.PromptRequest(SESSION, List.of(new AcpSchema.TextContent("wait"))))
				.cache();
			second.subscribe(response -> {
			}, error -> {
			});
			sleep(100);
			client.cancel(new AcpSchema.CancelNotification(SESSION)).block(TIMEOUT);
			assertThat(second.block(TIMEOUT).stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);
			assertThat(lateCallbackRan).isTrue();
		}
		finally {
			close(client, pair);
			agent.closeGracefully();
		}
	}

	private static AcpAsyncClient client(InMemoryTransportPair pair) {
		return AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
	}

	/** Initializes, opens the session, and starts the prompt, returning its cached answer. */
	private static Mono<PromptResponse> startPrompt(AcpAsyncClient client) {
		client.initialize().block(TIMEOUT);
		client.newSession(new AcpSchema.NewSessionRequest("/w", List.of())).block(TIMEOUT);
		Mono<PromptResponse> response = client.prompt(PROMPT).cache();
		response.subscribe(r -> {
		}, error -> {
		});
		sleep(100);
		return response;
	}

	private static void close(AcpAsyncClient client, InMemoryTransportPair pair) {
		client.closeGracefully().block(TIMEOUT);
		pair.closeGracefully().block(TIMEOUT);
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
