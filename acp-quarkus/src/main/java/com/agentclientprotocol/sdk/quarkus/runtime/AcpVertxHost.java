/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.http.server.AcpHttpEndpoint;
import com.agentclientprotocol.sdk.http.server.AcpHttpReply;
import com.agentclientprotocol.sdk.http.server.AcpWsHandler;
import com.agentclientprotocol.sdk.http.server.AcpWsHandshake;
import com.agentclientprotocol.sdk.integration.AcpAgentSettings;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.quarkus.AcpBuildTimeConfig;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The ACP endpoint on the Quarkus HTTP server: a Vert.x route at
 * {@code quarkus.acp.agent.transport.http.path} on Quarkus's own router, so the application's
 * port, TLS, HTTP security policies and observability apply to it. A host of the SDK's
 * framework-neutral {@link AcpHttpEndpoint}: every request on the path goes to the endpoint, a
 * WebSocket upgrade to its handshake, and the route only adapts Vert.x I/O. No servlet container
 * is involved. The extension adds it for an HTTP agent. Part of the extension's wiring; an
 * application does not use it directly.
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpVertxHost {

	private static final Logger logger = LoggerFactory.getLogger(AcpVertxHost.class);

	private final AcpHttpEndpoint endpoint;

	private final String path;

	AcpVertxHost(AcpAgentAssembly assembly, AcpRuntimeConfig config, AcpBuildTimeConfig buildConfig) {
		this.endpoint = AcpHttpEndpoint.create(AcpJsonMapper.createDefault(), assembly.factory(),
				options(config.agent().transport().http()));
		String configured = buildConfig.agent().transport().http().path();
		this.path = configured.startsWith("/") ? configured : "/" + configured;
	}

	/**
	 * The SDK options for the configured limits and allowed origins; an unset one keeps the SDK
	 * default. The bind address is Quarkus's own.
	 * @param http the configured limits
	 * @return the transport options
	 */
	static StreamableHttpAcpAgentTransportOptions options(AcpRuntimeConfig.AgentHttp http) {
		return AcpSettings.limits(AcpAgentSettings.builder(), http).build().toOptions(false);
	}

	/**
	 * Mounts the endpoint at its path, in the router's ordinary order: after Quarkus's own
	 * authentication and authorization handlers, so HTTP security policies on the path apply to
	 * every request and to the WebSocket handshake.
	 */
	void register(@Observes Router router) {
		endpoint.start();
		router.route(path).handler(this::handle);
	}

	/**
	 * Returns the endpoint this route serves.
	 * @return the endpoint
	 */
	public AcpHttpEndpoint endpoint() {
		return endpoint;
	}

	/**
	 * Drains the endpoint: SSE streams end with a closing comment, WebSockets close with 1001.
	 * @return completes when every connection has closed, within the shutdown timeout
	 */
	Mono<Void> closeGracefully() {
		return endpoint.closeGracefully();
	}

	int activeConnectionCount() {
		return endpoint.activeConnectionCount();
	}

	private void handle(RoutingContext context) {
		VertxExchange exchange = new VertxExchange(context);
		if (exchange.isWebSocketUpgrade()) {
			upgrade(context, exchange);
			return;
		}
		endpoint.handle(exchange).subscribe(reply -> write(context, reply), error -> {
			logger.warn("ACP request failed: {}", error.toString());
			if (!context.response().ended()) {
				context.response().setStatusCode(500).end();
			}
		});
	}

	private void upgrade(RoutingContext context, VertxExchange exchange) {
		AcpWsHandshake handshake = endpoint.webSocketHandshake(exchange);
		if (handshake instanceof AcpWsHandshake.Refused refused) {
			write(context, refused.reply());
			return;
		}
		AcpWsHandshake.Accepted accepted = (AcpWsHandshake.Accepted) handshake;
		accepted.headers().forEach(context.response()::putHeader);
		context.request().toWebSocket().onSuccess(socket -> open(socket, accepted)).onFailure(error -> {
			// No connection exists before open: nothing to close.
			logger.warn("ACP WebSocket upgrade failed: {}", error.getMessage());
		});
	}

	private static void open(ServerWebSocket socket, AcpWsHandshake.Accepted accepted) {
		AcpWsHandler handler = accepted.open(new VertxWsOutbound(socket));
		socket.textMessageHandler(handler::onText);
		socket.closeHandler(ignored -> {
			Short code = socket.closeStatusCode();
			handler.onClose(code != null ? code : 1006, socket.closeReason());
		});
		socket.exceptionHandler(handler::onError);
	}

	static void write(RoutingContext context, AcpHttpReply reply) {
		HttpServerResponse response = context.response();
		if (response.ended() || response.closed()) {
			return;
		}
		response.setStatusCode(reply.status());
		reply.headers().forEach(response::putHeader);
		if (reply instanceof AcpHttpReply.EventStream stream) {
			response.putHeader("Content-Type", AcpHttpReply.EVENT_STREAM);
			response.setChunked(true);
			stream.frames().subscribe(new VertxSseWriter(response));
			return;
		}
		if (reply instanceof AcpHttpReply.Body body) {
			response.putHeader("Content-Type", body.contentType() + "; charset=utf-8");
			response.end(Buffer.buffer(body.bodyBytes()));
			return;
		}
		response.end();
	}

}
