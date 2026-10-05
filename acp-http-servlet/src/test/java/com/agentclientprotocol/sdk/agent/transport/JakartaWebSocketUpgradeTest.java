/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.http.server.AcpWsHandler;
import com.agentclientprotocol.sdk.http.server.AcpWsHandshake;
import com.agentclientprotocol.sdk.http.server.AcpWsOutbound;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Jakarta WebSocket endpoint against a mocked session, for the paths a real container takes
 * only by timing: a close that fails at once, a close the container reports later, a failed
 * send, and events that arrive before the socket opened.
 */
class JakartaWebSocketUpgradeTest {

	private final RecordingHandshake handshake = new RecordingHandshake();

	private final JakartaWebSocketUpgrade.AcpEndpoint endpoint = new JakartaWebSocketUpgrade.AcpEndpoint(handshake);

	private final Session session = mock(Session.class);

	private final RemoteEndpoint.Async remote = mock(RemoteEndpoint.Async.class);

	@Test
	void theContainerIdleTimeoutIsTheHandshakes() {
		open();
		verify(session).setMaxIdleTimeout(Duration.ofSeconds(9).toMillis());
	}

	@Test
	void aCloseThatFailsCompletesAtOnce() throws Exception {
		doThrow(new IOException("broken pipe")).when(session).close(any(CloseReason.class));
		AcpWsOutbound outbound = open();

		CompletableFuture<Void> closed = outbound.close(1001, "idle timeout").toCompletableFuture();

		assertThat(closed).isCompleted();
	}

	@Test
	void aCloseCompletesWhenTheContainerReportsTheSocketClosed() {
		AcpWsOutbound outbound = open();

		CompletableFuture<Void> closed = outbound.close(1001, "idle timeout").toCompletableFuture();
		assertThat(closed).isNotDone();

		endpoint.onClose(session, new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "idle timeout"));
		assertThat(closed).isCompleted();
		assertThat(handshake.handler.closes).containsExactly(1001);
	}

	@Test
	void aFailedSendFailsItsStage() {
		SendResult failed = new SendResult(new IOException("reset"));
		doAnswer(invocation -> {
			invocation.<SendHandler>getArgument(1).onResult(failed);
			return null;
		}).when(remote).sendText(anyString(), any(SendHandler.class));
		AcpWsOutbound outbound = open();

		assertThat(outbound.sendText("{}").toCompletableFuture()).isCompletedExceptionally();
	}

	@Test
	void anErrorReachesTheHandler() {
		open();
		IOException error = new IOException("corrupt frame");

		endpoint.onError(session, error);

		assertThat(handshake.handler.errors).containsExactly(error);
	}

	@Test
	void eventsBeforeTheSocketOpenedAreIgnored() {
		endpoint.onError(session, new IOException("early"));
		endpoint.onClose(session, new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "early"));

		assertThat(handshake.handler.errors).isEmpty();
		assertThat(handshake.handler.closes).isEmpty();
	}

	private AcpWsOutbound open() {
		when(session.getAsyncRemote()).thenReturn(remote);
		endpoint.onOpen(session, mock(EndpointConfig.class));
		AcpWsOutbound outbound = handshake.outbound.get();
		assertThat(outbound).isNotNull();
		return outbound;
	}

	private static final class RecordingHandshake implements AcpWsHandshake.Accepted {

		final AtomicReference<@Nullable AcpWsOutbound> outbound = new AtomicReference<>();

		final RecordingHandler handler = new RecordingHandler();

		@Override
		public Map<String, String> headers() {
			return Map.of();
		}

		@Override
		public long maxTextMessageBytes() {
			return 1024;
		}

		@Override
		public Duration idleTimeout() {
			return Duration.ofSeconds(9);
		}

		@Override
		public AcpWsHandler open(AcpWsOutbound opened) {
			outbound.set(opened);
			return handler;
		}

	}

	private static final class RecordingHandler implements AcpWsHandler {

		final List<Integer> closes = new CopyOnWriteArrayList<>();

		final List<Throwable> errors = new CopyOnWriteArrayList<>();

		@Override
		public void onText(String text) {
		}

		@Override
		public void onClose(int code, @Nullable String reason) {
			closes.add(code);
		}

		@Override
		public void onError(Throwable error) {
			errors.add(error);
		}

	}

}
