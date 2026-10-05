/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.webflux;

import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.http.server.AcpHttpEndpoint;
import com.agentclientprotocol.sdk.http.server.AcpHttpExchange;
import com.agentclientprotocol.sdk.http.server.AcpWsHandshake;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.test.http.AcpHttpTransportTck;
import com.agentclientprotocol.sdk.test.http.HttpProbes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebFlux reports no completion per WebSocket frame, so the host sends a frame only when WebFlux
 * asks for one. A client that reads nothing therefore leaves the agent's frames in the
 * endpoint's bounded queue, and the endpoint's rule for a full queue applies: the socket closes
 * with 1011, rather than the frames piling up in the host without bound.
 */
class WebSocketBackpressureTest {

	private static final int PENDING_FRAMES = 4;

	private static final String NEW_SESSION = """
			{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"/","mcpServers":[]}}""";

	private final AcpHttpEndpoint endpoint = AcpHttpEndpoint.create(AcpJsonMapper.createDefault(),
			AcpHttpTransportTck.agentFactory(),
			StreamableHttpAcpAgentTransportOptions.builder().maxWebSocketPendingFrames(PENDING_FRAMES).build());

	private @Nullable Disposable running;

	@AfterEach
	void close() {
		Disposable current = running;
		if (current != null) {
			current.dispose();
		}
		endpoint.closeGracefully().block(Duration.ofSeconds(10));
	}

	private FakeWebSocketSession open(long initialDemand) {
		AcpWsHandshake handshake = endpoint.webSocketHandshake(new Upgrade());
		assertThat(handshake).isInstanceOf(AcpWsHandshake.Accepted.class);
		FakeWebSocketSession session = new FakeWebSocketSession(initialDemand);
		running = new WebFluxWsHandler((AcpWsHandshake.Accepted) handshake).handle(session).subscribe();
		return session;
	}

	private static String prompt(String text) {
		return "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"s-1\","
				+ "\"prompt\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}}";
	}

	@Test
	void aClientThatReadsNothingOverflowsTheEndpointsQueueAndTheSocketClosesWith1011() {
		FakeWebSocketSession session = open(2);
		session.receive(HttpProbes.INITIALIZE);
		session.receive(NEW_SESSION);
		waitUntil(() -> session.sent.size() == 2);
		// Far more updates than the queue holds; WebFlux asks for none of them.
		session.receive(prompt("updates:100"));
		waitUntil(() -> !session.closes.isEmpty());
		assertThat(session.closes).hasSize(1);
		assertThat(session.closes.get(0).getCode()).isEqualTo(1011);
		assertThat(session.sent).as("nothing beyond what WebFlux asked for").hasSize(2);
	}

	@Test
	void aFrameWaitsForDemandAndGoesOutWhenWebFluxAsks() throws InterruptedException {
		FakeWebSocketSession session = open(1);
		session.receive(HttpProbes.INITIALIZE);
		waitUntil(() -> session.sent.size() == 1);
		session.receive(NEW_SESSION);
		session.receive(prompt("updates:3"));
		Thread.sleep(200);
		assertThat(session.sent).hasSize(1);
		session.request(Long.MAX_VALUE);
		// session/new's answer, three updates and the prompt's answer.
		waitUntil(() -> session.sent.size() == 6);
		assertThat(session.sent.get(1)).contains("\"id\":2").contains("s-1");
		assertThat(session.sent.get(5)).contains("\"id\":3").contains("end_turn");
		assertThat(session.closes).isEmpty();
	}

	@Test
	void theEndpointsCloseCodeGoesOutBeforeTheFramesComplete() {
		FakeWebSocketSession session = open(Long.MAX_VALUE);
		// The first message must be initialize: the endpoint closes with 1002.
		session.receive(NEW_SESSION);
		waitUntil(() -> !session.closes.isEmpty());
		assertThat(session.closes).extracting(status -> status.getCode()).containsExactly(1002);
	}

	@Test
	void aClientCloseClosesTheConnection() {
		FakeWebSocketSession session = open(Long.MAX_VALUE);
		session.receive(HttpProbes.INITIALIZE);
		waitUntil(() -> session.sent.size() == 1);
		assertThat(endpoint.activeConnectionCount()).isEqualTo(1);
		session.clientCloses(1000);
		waitUntil(() -> endpoint.activeConnectionCount() == 0);
		assertThat(session.closes).as("the client closed; the host sends no close of its own").isEmpty();
	}

	private static void waitUntil(BooleanSupplier condition) {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out");
			}
			
			try {
				Thread.sleep(10);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		}
	}

	/** A WebSocket upgrade request with no Origin, as a non-browser client sends it. */
	private static final class Upgrade implements AcpHttpExchange {

		private static final Map<String, String> HEADERS = Map.of("upgrade", "websocket");

		@Override
		public String method() {
			return "GET";
		}

		@Override
		public @Nullable String header(String name) {
			return HEADERS.get(name.toLowerCase(java.util.Locale.ROOT));
		}

		@Override
		public Mono<byte[]> body(long maxBytes) {
			return Mono.just(new byte[0]);
		}

		@Override
		public @Nullable Principal principal() {
			return null;
		}

	}


}
