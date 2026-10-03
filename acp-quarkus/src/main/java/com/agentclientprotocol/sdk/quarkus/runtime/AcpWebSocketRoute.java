/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import com.agentclientprotocol.sdk.quarkus.AcpBuildTimeConfig;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Accepts WebSocket upgrades on the Streamable HTTP endpoint's path, on the Quarkus HTTP
 * server itself: a request carrying {@code Upgrade: websocket} becomes an ACP connection,
 * and every other request goes on to the servlet. Each connection runs one agent from
 * the shared factory, as the SDK's own listener does.
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpWebSocketRoute {

	private static final Logger logger = LoggerFactory.getLogger(AcpWebSocketRoute.class);

	/** The upgrade response header naming the connection, as on the SDK listener. */
	static final String HEADER_CONNECTION_ID = "Acp-Connection-Id";

	private final AcpHttpEndpoint endpoint;

	private final String path;

	private final Map<String, VertxWebSocketConnection> connections = new ConcurrentHashMap<>();

	AcpWebSocketRoute(AcpHttpEndpoint endpoint, AcpBuildTimeConfig config) {
		this.endpoint = endpoint;
		String contextPath = ConfigProvider.getConfig()
			.getOptionalValue("quarkus.servlet.context-path", String.class)
			.orElse("");
		this.path = join(contextPath, config.agent().transport().http().path());
	}

	static String join(String contextPath, String path) {
		String base = contextPath.endsWith("/") ? contextPath.substring(0, contextPath.length() - 1) : contextPath;
		return base + (path.startsWith("/") ? path : "/" + path);
	}

	void register(@Observes Router router) {
		router.route(path).order(Integer.MIN_VALUE).handler(this::route);
	}

	private void route(RoutingContext context) {
		HttpServerRequest request = context.request();
		if (!isUpgrade(request)) {
			context.next();
			return;
		}
		String id = UUID.randomUUID().toString();
		request.response().putHeader(HEADER_CONNECTION_ID, id);
		request.toWebSocket().onSuccess(socket -> accept(id, socket)).onFailure(error -> {
			logger.warn("ACP WebSocket upgrade failed: {}", error.getMessage());
			endpointError(error);
		});
	}

	static boolean isUpgrade(HttpServerRequest request) {
		String upgrade = request.getHeader(HttpHeaders.UPGRADE);
		return upgrade != null && "websocket".equalsIgnoreCase(upgrade);
	}

	private void accept(String id, ServerWebSocket socket) {
		VertxWebSocketConnection connection = new VertxWebSocketConnection(id, socket, endpoint.jsonMapper(),
				endpoint.options(), closed -> connections.remove(closed.id(), closed), this::endpointError);
		logger.info("ACP WebSocket client connected from {}", socket.remoteAddress());
		connections.put(id, connection);
		// Nothing is read until the connection's agent has started.
		socket.pause();
		socket.textMessageHandler(connection::receive);
		socket.closeHandler(ignored -> {
			logger.info("ACP WebSocket client disconnected: {} - {}", socket.closeStatusCode(), socket.closeReason());
			connection.closed();
		});
		socket.exceptionHandler(error -> {
			logger.debug("ACP WebSocket error on connection {}: {}", id, error.getMessage());
			connection.signalException(error);
			connection.close(VertxWebSocketConnection.SERVER_ERROR, "WebSocket error");
		});
		connection.start(endpoint.agentFactory()).subscribe(ignored -> {
		}, error -> {
			endpointError(error);
			connection.close(VertxWebSocketConnection.SERVER_ERROR, "agent failed to start");
		}, socket::resume);
	}

	private void endpointError(Throwable error) {
		logger.error("Streamable HTTP ACP connection error", error);
	}

	/**
	 * Closes every WebSocket connection, each agent gracefully, within the endpoint's
	 * shutdown timeout; one that has not closed by then is closed at once.
	 * @return completes when every connection has closed
	 */
	Mono<Void> closeGracefully() {
		List<VertxWebSocketConnection> closing = List.copyOf(connections.values());
		connections.clear();
		List<Mono<Void>> closures = new ArrayList<>();
		closing.forEach(connection -> closures.add(connection.closeGracefully()));
		Duration timeout = endpoint.options().shutdownTimeout();
		return Mono.whenDelayError(closures)
			.timeout(timeout, AcpSchedulers.timeouts())
			.onErrorResume(TimeoutException.class, timedOut -> {
				logger.warn("ACP WebSocket connections did not close within {}; closing them now", timeout);
				closing.forEach(VertxWebSocketConnection::closeNow);
				return Mono.empty();
			});
	}

	int activeConnectionCount() {
		return connections.size();
	}

}
