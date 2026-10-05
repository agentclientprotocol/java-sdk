/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.webflux;

import java.util.concurrent.CompletableFuture;

import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbound side of one socket: a frame goes out only on WebFlux's demand, one at a time,
 * and nothing goes out after the close.
 */
class WebFluxWsOutboundTest {

	@Test
	void aSendWaitsForDemandAndCompletesWhenTheFrameGoesOut() {
		FakeWebSocketSession session = new FakeWebSocketSession(0);
		WebFluxWsOutbound outbound = new WebFluxWsOutbound(session);
		// Before WebFlux subscribes, a frame waits too.
		CompletableFuture<Void> first = outbound.sendText("one").toCompletableFuture();
		Disposable sending = session.send(outbound.frames().map(session::textMessage)).subscribe();
		assertThat(first).isNotDone();
		session.request(1);
		assertThat(first).isCompleted();
		assertThat(session.sent).containsExactly("one");
		sending.dispose();
	}

	@Test
	void theEndpointSendsOneFrameAtATime() {
		FakeWebSocketSession session = new FakeWebSocketSession(0);
		WebFluxWsOutbound outbound = new WebFluxWsOutbound(session);
		Disposable sending = session.send(outbound.frames().map(session::textMessage)).subscribe();
		CompletableFuture<Void> first = outbound.sendText("one").toCompletableFuture();
		assertThat(outbound.sendText("two").toCompletableFuture()).isCompletedExceptionally();
		assertThat(first).isNotDone();
		sending.dispose();
	}

	@Test
	void demandBeyondLongMaxStaysUnbounded() {
		FakeWebSocketSession session = new FakeWebSocketSession(Long.MAX_VALUE);
		WebFluxWsOutbound outbound = new WebFluxWsOutbound(session);
		Disposable sending = session.send(outbound.frames().map(session::textMessage)).subscribe();
		session.request(Long.MAX_VALUE);
		for (int i = 0; i < 5; i++) {
			assertThat(outbound.sendText("m" + i).toCompletableFuture()).isCompleted();
		}
		assertThat(session.sent).hasSize(5);
		sending.dispose();
	}

	@Test
	void theCloseFailsTheWaitingSendAndEveryLaterOne() {
		FakeWebSocketSession session = new FakeWebSocketSession(0);
		WebFluxWsOutbound outbound = new WebFluxWsOutbound(session);
		Disposable sending = session.send(outbound.frames().map(session::textMessage)).subscribe();
		CompletableFuture<Void> waiting = outbound.sendText("one").toCompletableFuture();
		outbound.close(1001, "going away");
		outbound.close(1000, "again");
		assertThat(waiting).isCompletedExceptionally();
		assertThat(outbound.sendText("two").toCompletableFuture()).isCompletedExceptionally();
		assertThat(session.closes).extracting(status -> status.getCode()).containsExactly(1001);
		session.request(1);
		assertThat(session.sent).isEmpty();
		assertThat(sending.isDisposed()).as("the frames completed after the close").isTrue();
	}

	@Test
	void aSocketThatClosedSendsNothing() {
		FakeWebSocketSession session = new FakeWebSocketSession(1);
		WebFluxWsOutbound outbound = new WebFluxWsOutbound(session);
		outbound.closed();
		assertThat(outbound.sendText("one").toCompletableFuture()).isCompletedExceptionally();
		outbound.close(1000, "after");
		assertThat(session.closes).isEmpty();
	}

	@Test
	void aSendThatWebFluxCancelledFailsTheWaitingFrame() {
		FakeWebSocketSession session = new FakeWebSocketSession(0);
		WebFluxWsOutbound outbound = new WebFluxWsOutbound(session);
		Disposable sending = session.send(outbound.frames().map(session::textMessage)).subscribe();
		CompletableFuture<Void> waiting = outbound.sendText("one").toCompletableFuture();
		sending.dispose();
		assertThat(waiting).isCompletedExceptionally();
	}

	@Test
	void nettysRefusalsOfAnOversizedMessageAreTooBig() {
		assertThat(WebSocketUpgrades.isMessageTooBig(new TooLongFrameException("too long"))).isTrue();
		assertThat(WebSocketUpgrades.isMessageTooBig(
				new CorruptedWebSocketFrameException(WebSocketCloseStatus.MESSAGE_TOO_BIG, "too big"))).isTrue();
		assertThat(WebSocketUpgrades.isMessageTooBig(
				new CorruptedWebSocketFrameException(WebSocketCloseStatus.PROTOCOL_ERROR, "bad frame"))).isFalse();
		assertThat(WebSocketUpgrades.isMessageTooBig(new IllegalStateException("other"))).isFalse();
	}

	@Test
	void theFrameLimitIsOneByteOverTheEndpointsWithinAnInt() {
		assertThat(WebSocketUpgrades.frameLimit(65_536)).isEqualTo(65_537);
		assertThat(WebSocketUpgrades.frameLimit(Long.MAX_VALUE - 1)).isEqualTo(Integer.MAX_VALUE);
	}

}
