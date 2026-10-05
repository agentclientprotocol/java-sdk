/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.agentclientprotocol.sdk.http.server.AcpWsHandler;
import com.agentclientprotocol.sdk.http.server.AcpWsHandshake;
import com.agentclientprotocol.sdk.http.server.AcpWsOutbound;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.CloseReason;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Upgrades an accepted handshake with Jakarta WebSocket 2.1's
 * {@code ServerContainer.upgradeHttpToWebSocket}, on whichever implementation the container
 * installed (Tomcat's, Jetty's, Undertow's), and carries the socket's frames to and from the
 * endpoint. Only this class touches the Jakarta WebSocket API, and the servlet loads it only for
 * an upgrade request, so a container without it serves HTTP all the same.
 *
 * @author Mark Pollack
 */
final class JakartaWebSocketUpgrade {

	private static final Logger logger = LoggerFactory.getLogger(JakartaWebSocketUpgrade.class);

	private static final String SERVER_CONTAINER_ATTRIBUTE = "jakarta.websocket.server.ServerContainer";

	private static final boolean API_PRESENT = isPresent("jakarta.websocket.server.ServerContainer");

	private JakartaWebSocketUpgrade() {
	}

	private static boolean isPresent(String className) {
		try {
			Class.forName(className, false, JakartaWebSocketUpgrade.class.getClassLoader());
			return true;
		}
		catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	/**
	 * Upgrades the request.
	 * @return false when the container has no Jakarta WebSocket implementation
	 */
	static boolean upgrade(HttpServletRequest request, HttpServletResponse response, AcpWsHandshake.Accepted accepted)
			throws ServletException, IOException {
		if (!API_PRESENT) {
			return false;
		}
		return Upgrader.upgrade(request, response, accepted);
	}

	/** Holds the Jakarta WebSocket types, so they load only once the API is known to be present. */
	private static final class Upgrader {

		static boolean upgrade(HttpServletRequest request, HttpServletResponse response,
				AcpWsHandshake.Accepted accepted) throws ServletException, IOException {
			Object attribute = request.getServletContext().getAttribute(SERVER_CONTAINER_ATTRIBUTE);
			if (!(attribute instanceof ServerContainer container)) {
				return false;
			}
			AcpEndpoint endpoint = new AcpEndpoint(accepted);
			String path = request.getRequestURI();
			ServerEndpointConfig config = ServerEndpointConfig.Builder.create(AcpEndpoint.class, path)
				.configurator(new ServerEndpointConfig.Configurator() {

					@Override
					public <T> T getEndpointInstance(Class<T> endpointClass) {
						return endpointClass.cast(endpoint);
					}

					@Override
					public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest handshakeRequest,
							HandshakeResponse handshakeResponse) {
						accepted.headers().forEach((name, value) -> handshakeResponse.getHeaders().put(name, List.of(value)));
					}

				})
				.build();
			try {
				container.upgradeHttpToWebSocket(request, response, config, Map.of());
			}
			catch (DeploymentException e) {
				throw new ServletException("ACP WebSocket upgrade failed", e);
			}
			return true;
		}

	}

	/**
	 * One accepted socket. Text messages arrive in parts, assembled here up to the endpoint's
	 * limit, so the container's text buffer (8 KB on Tomcat, allocated per socket) need not be
	 * raised to the limit.
	 */
	public static final class AcpEndpoint extends Endpoint {

		private final AcpWsHandshake.Accepted accepted;

		private volatile @Nullable AcpWsHandler handler;

		AcpEndpoint(AcpWsHandshake.Accepted accepted) {
			this.accepted = accepted;
		}

		@Override
		public void onOpen(Session session, EndpointConfig config) {
			session.setMaxIdleTimeout(accepted.idleTimeout().toMillis());
			AcpWsHandler opened = accepted.open(new SessionOutbound(session));
			this.handler = opened;
			long max = accepted.maxTextMessageBytes();
			StringBuilder message = new StringBuilder();
			session.addMessageHandler(String.class, (MessageHandler.Partial<String>) (part, last) -> {
				if (message.length() + part.length() > max) {
					// Over the limit already in characters, so over it in bytes too; the endpoint
					// checks the assembled message's bytes.
					message.setLength(0);
					close(session, CloseReason.CloseCodes.TOO_BIG, "message too big");
					return;
				}
				message.append(part);
				if (last) {
					String text = message.toString();
					message.setLength(0);
					opened.onText(text);
				}
			});
		}

		@Override
		public void onClose(Session session, CloseReason closeReason) {
			AcpWsHandler current = handler;
			if (current != null) {
				current.onClose(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());
			}
		}

		@Override
		public void onError(Session session, Throwable error) {
			AcpWsHandler current = handler;
			if (current != null) {
				current.onError(error);
			}
		}

	}

	private static void close(Session session, CloseReason.CloseCode code, String reason) {
		try {
			session.close(new CloseReason(code, reason));
		}
		catch (IOException | IllegalStateException e) {
			logger.debug("Closing an ACP WebSocket failed: {}", e.toString());
		}
	}

	/** Sends through the socket's async remote; the endpoint sends one frame at a time. */
	private static final class SessionOutbound implements AcpWsOutbound {

		private final Session session;

		SessionOutbound(Session session) {
			this.session = session;
		}

		@Override
		public CompletionStage<Void> sendText(String text) {
			CompletableFuture<Void> sent = new CompletableFuture<>();
			try {
				session.getAsyncRemote().sendText(text, result -> {
					if (result.isOK()) {
						sent.complete(null);
					}
					else {
						sent.completeExceptionally(result.getException());
					}
				});
			}
			catch (RuntimeException e) {
				sent.completeExceptionally(e);
			}
			return sent;
		}

		@Override
		public void close(int code, String reason) {
			JakartaWebSocketUpgrade.close(session, CloseReason.CloseCodes.getCloseCode(code), reason);
		}

	}

}
