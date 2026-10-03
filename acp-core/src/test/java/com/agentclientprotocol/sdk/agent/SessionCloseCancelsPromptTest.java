/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code session/close} cancels the session's ongoing work as if {@code session/cancel} had been
 * called, and then closes it (ACP v1 schema, {@code CloseSessionRequest}: "the agent
 * <b>must</b> cancel any ongoing work related to the session (treat it as if
 * {@code session/cancel} was called) and then free up any resources associated with the
 * session").
 */
class SessionCloseCancelsPromptTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final InMemoryTransportPair pair = InMemoryTransportPair.create();

	private final List<String> events = new CopyOnWriteArrayList<>();

	private AcpAsyncAgent agent;

	private AcpAsyncClient client;

	@AfterEach
	void tearDown() {
		if (this.client != null) {
			this.client.close();
		}
		if (this.agent != null) {
			this.agent.close();
		}
	}

	@Test
	void closingASessionWithAPromptInFlightCancelsThePromptThenCloses() {
		Sinks.Empty<Void> promptStarted = Sinks.empty();
		Sinks.Empty<Void> cancelled = Sinks.empty();
		this.agent = AcpAgent.async(this.pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("s-1", null)))
			.promptHandler((request, context) -> {
				promptStarted.tryEmitEmpty();
				// Works until cancelled, as an agent's prompt handler does.
				return cancelled.asMono().then(Mono.fromCallable(() -> {
					this.events.add("prompt answered");
					return new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED);
				}));
			})
			.cancelHandler(notification -> Mono.fromRunnable(() -> {
				this.events.add("cancel " + notification.sessionId());
				cancelled.tryEmitEmpty();
			}))
			.closeSessionHandler(request -> Mono.fromCallable(() -> {
				this.events.add("close " + request.sessionId());
				return new AcpSchema.CloseSessionResponse();
			}))
			.build();
		this.agent.start().block(TIMEOUT);
		this.client = AcpClient.async(this.pair.clientTransport()).requestTimeout(TIMEOUT).build();
		this.client.initialize().block(TIMEOUT);
		this.client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

		Mono<AcpSchema.PromptResponse> prompt = this.client
			.prompt(new AcpSchema.PromptRequest("s-1", List.of(new AcpSchema.TextContent("work"))))
			.cache();
		prompt.subscribe(response -> {
		}, error -> {
		});
		promptStarted.asMono().block(TIMEOUT);

		this.client.closeSession(new AcpSchema.CloseSessionRequest("s-1")).block(TIMEOUT);

		assertThat(prompt.block(TIMEOUT).stopReason()).isEqualTo(AcpSchema.StopReason.CANCELLED);
		assertThat(this.events).containsExactly("cancel s-1", "prompt answered", "close s-1");
	}

	@Test
	void closingAnIdleSessionClosesItAndTellsTheCancelHandler() {
		this.agent = AcpAgent.async(this.pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("s-1", null)))
			.cancelHandler(notification -> Mono.fromRunnable(() -> this.events.add("cancel " + notification.sessionId())))
			.closeSessionHandler(request -> Mono.fromCallable(() -> {
				this.events.add("close " + request.sessionId());
				return new AcpSchema.CloseSessionResponse();
			}))
			.build();
		this.agent.start().block(TIMEOUT);
		this.client = AcpClient.async(this.pair.clientTransport()).requestTimeout(TIMEOUT).build();
		this.client.initialize().block(TIMEOUT);

		this.client.closeSession(new AcpSchema.CloseSessionRequest("s-1")).block(TIMEOUT);

		assertThat(this.events).containsExactly("cancel s-1", "close s-1");
	}

}
