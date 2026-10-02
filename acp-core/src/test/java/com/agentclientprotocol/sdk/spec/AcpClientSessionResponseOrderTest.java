/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Empty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A response does not complete its caller until every notification that arrived before it on
 * the session has been delivered to its handler: the prompt turn's updates come before its
 * response, and a caller reading what its update handler collected once {@code prompt()}
 * returns must find all of them. Driven by hand, one inbound message at a time, so every
 * outcome is decided by the order of the messages rather than by timing.
 */
class AcpClientSessionResponseOrderTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String UPDATE = "session/update";

	private static final TypeRef<String> STRING = new TypeRef<>() {
	};

	private final MockAcpClientTransport transport = new MockAcpClientTransport();

	/** The handler of the update being delivered; completing it finishes that delivery. */
	private final AtomicReference<Empty<Void>> handling = new AtomicReference<>();

	private AcpClientSession session;

	@AfterEach
	void close() {
		if (this.session != null) {
			this.session.close();
		}
	}

	private AcpClientSession sessionWithUpdateHandler(Function<Object, Mono<Void>> handler) {
		this.session = new AcpClientSession(TIMEOUT, this.transport, Map.of(), Map.of(UPDATE, handler::apply),
				Function.identity());
		return this.session;
	}

	/** An update handler that finishes only when the test completes {@link #handling}. */
	private Mono<Void> slowHandler(Object params) {
		Empty<Void> done = Sinks.empty();
		this.handling.set(done);
		return done.asMono();
	}

	private void receiveUpdate() {
		this.transport.simulateIncomingMessage(
				new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION, UPDATE, Map.of()));
	}

	private void receiveResponse(Object id, String result) {
		this.transport.simulateIncomingMessage(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, result, null));
	}

	@Test
	void aResponseWaitsForTheUpdateBeforeItToBeHandled() {
		sessionWithUpdateHandler(this::slowHandler);
		var response = this.session.sendRequest("session/prompt", Map.of(), STRING).toFuture();
		Object id = this.transport.getLastSentMessageAsRequest().id();

		receiveUpdate();
		receiveResponse(id, "end_turn");

		assertThat(response).as("completed while the update before it was still being handled").isNotDone();
		this.handling.get().tryEmitEmpty();
		assertThat(response).isCompletedWithValue("end_turn");
	}

	@Test
	void aResponseWithNoUpdateStillBeingHandledCompletesAtOnce() {
		sessionWithUpdateHandler(params -> Mono.empty());
		var response = this.session.sendRequest("session/prompt", Map.of(), STRING).toFuture();
		Object id = this.transport.getLastSentMessageAsRequest().id();

		receiveUpdate();
		receiveResponse(id, "end_turn");

		assertThat(response).isCompletedWithValue("end_turn");
	}

	@Test
	void anUpdateAfterTheResponseDoesNotHoldIt() {
		sessionWithUpdateHandler(this::slowHandler);
		var response = this.session.sendRequest("session/prompt", Map.of(), STRING).toFuture();
		Object id = this.transport.getLastSentMessageAsRequest().id();

		receiveResponse(id, "end_turn");
		receiveUpdate();

		assertThat(response).isCompletedWithValue("end_turn");
	}

	/**
	 * An update handler that sends a request and waits for its answer, while the agent sends
	 * another update first: holding that answer behind the updates would deadlock the handler.
	 */
	@Test
	void aRequestSentByAHandlerStillRunningIsNotHeldBehindIt() {
		AtomicReference<java.util.concurrent.CompletableFuture<String>> fromHandler = new AtomicReference<>();
		sessionWithUpdateHandler(params -> {
			if (fromHandler.get() != null) {
				return Mono.empty();
			}
			Mono<String> request = this.session.sendRequest("session/set_mode", Map.of(), STRING).cache();
			fromHandler.set(request.toFuture());
			return request.then();
		});

		receiveUpdate();
		Object id = this.transport.getLastSentMessageAsRequest().id();
		receiveUpdate();
		receiveResponse(id, "ok");

		assertThat(fromHandler.get()).isCompletedWithValue("ok");
	}

	@Test
	void closeReleasesAResponseHeldBehindAnUpdate() {
		sessionWithUpdateHandler(this::slowHandler);
		var response = this.session.sendRequest("session/prompt", Map.of(), STRING).toFuture();
		Object id = this.transport.getLastSentMessageAsRequest().id();
		receiveUpdate();
		receiveResponse(id, "end_turn");

		this.session.close();

		assertThat(response).isCompletedWithValue("end_turn");
	}

	@Test
	void closeGracefullyDeliversTheUpdateThenCompletesTheResponse() {
		sessionWithUpdateHandler(this::slowHandler);
		var response = this.session.sendRequest("session/prompt", Map.of(), STRING).toFuture();
		Object id = this.transport.getLastSentMessageAsRequest().id();
		receiveUpdate();
		receiveResponse(id, "end_turn");

		var closed = this.session.closeGracefully().toFuture();
		assertThat(closed).isNotDone();
		assertThat(response).isNotDone();
		this.handling.get().tryEmitEmpty();

		assertThat(response).isCompletedWithValue("end_turn");
		assertThat(closed).succeedsWithin(TIMEOUT);
	}

}
