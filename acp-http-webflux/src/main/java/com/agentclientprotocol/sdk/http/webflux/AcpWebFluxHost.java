/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.webflux;

import java.util.Optional;

import com.agentclientprotocol.sdk.http.server.AcpHttpEndpoint;
import com.agentclientprotocol.sdk.http.server.AcpHttpReply;
import com.agentclientprotocol.sdk.http.server.AcpWsHandshake;
import com.agentclientprotocol.sdk.util.Assert;
import reactor.core.publisher.Mono;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.server.ServerWebExchange;

/**
 * The ACP endpoint in a Spring WebFlux application: Streamable HTTP and WebSocket on one path of
 * the application's own server (Reactor Netty, Tomcat or Jetty), as a {@link RouterFunction}.
 * Because the endpoint is a route like any other, the application's {@code WebFilter}s apply to
 * it: Spring Security's {@code SecurityWebFilterChain} protects it, over HTTP and on the
 * WebSocket handshake, and the authenticated principal reaches the endpoint; observations and
 * access logs cover it too.
 *
 * <pre>{@code
 * AcpHttpEndpoint endpoint = AcpHttpEndpoint.create(AcpJsonMapper.createDefault(), agentFactory,
 *         StreamableHttpAcpAgentTransportOptions.builder().build());
 * endpoint.start();
 * RouterFunction<ServerResponse> acp = new AcpWebFluxHost(endpoint).routerFunction("/acp");
 * }</pre>
 *
 * <p>The host is a host of {@link AcpHttpEndpoint}, which holds every protocol rule: the status
 * table, routing, the SSE mailboxes and keep-alive, the WebSocket send queue and close codes,
 * and the {@code Origin} check. The host only adapts I/O: it resolves the request's principal,
 * hands the request to the endpoint, writes its reply (SSE frames flushed one by one), and
 * carries WebSocket frames between WebFlux's {@code WebSocketSession} and the endpoint, sending
 * a frame only when WebFlux asks for one, so the endpoint's bound on pending frames holds. On
 * Reactor Netty it raises the WebSocket frame limit above the endpoint's message limit, so an
 * oversized message is the endpoint's to refuse (close code 1009); on Tomcat and Jetty the
 * container's own WebSocket message limit applies as well.
 *
 * <p>The application drives the endpoint's lifecycle: {@link AcpHttpEndpoint#start()} when the
 * server starts, and {@link AcpHttpEndpoint#closeGracefully()} before the server's graceful
 * shutdown, since open SSE streams and WebSockets never finish by themselves. The Spring Boot
 * autoconfiguration does both.
 *
 * @author Mark Pollack
 */
public final class AcpWebFluxHost {

	private final AcpHttpEndpoint endpoint;

	/**
	 * Creates a host of the endpoint.
	 * @param endpoint the endpoint to serve
	 * @throws IllegalArgumentException if the endpoint is null
	 */
	public AcpWebFluxHost(AcpHttpEndpoint endpoint) {
		Assert.notNull(endpoint, "The endpoint must not be null");
		this.endpoint = endpoint;
	}

	/**
	 * Returns the endpoint this host serves.
	 * @return the endpoint
	 */
	public AcpHttpEndpoint endpoint() {
		return endpoint;
	}

	/**
	 * Returns a route serving the endpoint on {@code path}, every method and the WebSocket
	 * upgrade alike: register it as a {@code RouterFunction} bean, or combine it with the
	 * application's own routes.
	 * @param path the path, such as {@code /acp}
	 * @return the route
	 * @throws IllegalArgumentException if the path is null or empty
	 */
	public RouterFunction<ServerResponse> routerFunction(String path) {
		Assert.hasText(path, "The path must not be empty");
		String mounted = path.startsWith("/") ? path : "/" + path;
		return RouterFunctions.route(RequestPredicates.path(mounted),
				request -> ServerResponse.ok().build((exchange, context) -> handle(exchange)));
	}

	/**
	 * Answers one request on the endpoint's path, as a {@code WebHandler} would: a WebSocket
	 * upgrade goes to the endpoint's handshake and, if accepted, to WebFlux's WebSocket support;
	 * any other request to the endpoint. The principal is resolved first, so the endpoint reads it
	 * on the calling thread.
	 * @param exchange the request and its response
	 * @return completes when the response has been written, or the WebSocket has closed
	 */
	public Mono<Void> handle(ServerWebExchange exchange) {
		return exchange.getPrincipal()
			.map(Optional::of)
			.defaultIfEmpty(Optional.empty())
			.flatMap(principal -> handle(exchange, new WebFluxExchange(exchange, principal.orElse(null))));
	}

	private Mono<Void> handle(ServerWebExchange exchange, WebFluxExchange acp) {
		if (acp.isWebSocketUpgrade()) {
			AcpWsHandshake handshake = endpoint.webSocketHandshake(acp);
			if (handshake instanceof AcpWsHandshake.Refused refused) {
				return write(exchange.getResponse(), refused.reply());
			}
			AcpWsHandshake.Accepted accepted = (AcpWsHandshake.Accepted) handshake;
			accepted.headers().forEach(exchange.getResponse().getHeaders()::set);
			HandshakeWebSocketService webSockets = WebSocketUpgrades.service(exchange, accepted);
			return webSockets.handleRequest(exchange, new WebFluxWsHandler(accepted));
		}
		return endpoint.handle(acp).flatMap(reply -> write(exchange.getResponse(), reply));
	}

	static Mono<Void> write(ServerHttpResponse response, AcpHttpReply reply) {
		response.setStatusCode(HttpStatus.valueOf(reply.status()));
		reply.headers().forEach(response.getHeaders()::set);
		if (reply instanceof AcpHttpReply.EventStream stream) {
			response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
			// One flush per frame: an SSE event reaches the client as soon as it is written.
			return response.writeAndFlushWith(
					stream.frames().map(frame -> Mono.just(response.bufferFactory().wrap(frame.encode()))));
		}
		if (reply instanceof AcpHttpReply.Body body) {
			response.getHeaders().setContentType(MediaType.parseMediaType(body.contentType() + ";charset=utf-8"));
			DataBuffer buffer = response.bufferFactory().wrap(body.bodyBytes());
			return response.writeWith(Mono.just(buffer));
		}
		return response.setComplete();
	}

}
