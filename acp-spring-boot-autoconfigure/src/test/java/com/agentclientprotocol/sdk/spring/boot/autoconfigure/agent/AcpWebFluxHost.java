/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;


import java.security.Principal;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.agentclientprotocol.sdk.http.server.AcpHttpEndpoint;
import com.agentclientprotocol.sdk.http.server.AcpHttpExchange;
import com.agentclientprotocol.sdk.http.server.AcpHttpReply;
import com.agentclientprotocol.sdk.http.server.AcpWsHandler;
import com.agentclientprotocol.sdk.http.server.AcpWsHandshake;
import com.agentclientprotocol.sdk.http.server.AcpWsOutbound;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebHandler;

/**
 * SPIKE (not shipped): the ACP endpoint as a WebFlux {@link WebHandler} on one path. Every
 * request goes to the endpoint; an upgrade to its handshake and then to WebFlux's own
 * {@link HandshakeWebSocketService} (Reactor Netty, Tomcat or Jetty reactive).
 */
final class AcpWebFluxHost implements WebHandler {

	private final AcpHttpEndpoint endpoint;

	private final HandshakeWebSocketService webSockets;

	AcpWebFluxHost(AcpHttpEndpoint endpoint) {
		this.endpoint = endpoint;
		// Netty's frame limit (64 KB by default) above the endpoint's, so the endpoint's own size
		// rule (1009) applies rather than Netty's decoder failing the socket (1011). Reactor
		// Netty only; Tomcat and Jetty reactive take theirs from the container.
		int max = (int) Math.min(Integer.MAX_VALUE, endpoint.options().maxPostBodyBytes() + 1);
		this.webSockets = new HandshakeWebSocketService(
				new org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy(
						() -> reactor.netty.http.server.WebsocketServerSpec.builder().maxFramePayloadLength(max)));
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange) {
		// The contract's principal is synchronous; WebFlux resolves it reactively, so first.
		return exchange.getPrincipal()
			.map(Optional::of)
			.defaultIfEmpty(Optional.empty())
			.flatMap(principal -> handle(exchange, new Exchange(exchange, principal.orElse(null))));
	}

	private Mono<Void> handle(ServerWebExchange exchange, Exchange acp) {
		if (acp.isWebSocketUpgrade()) {
			AcpWsHandshake handshake = endpoint.webSocketHandshake(acp);
			if (handshake instanceof AcpWsHandshake.Refused refused) {
				return write(exchange.getResponse(), refused.reply());
			}
			AcpWsHandshake.Accepted accepted = (AcpWsHandshake.Accepted) handshake;
			accepted.headers().forEach(exchange.getResponse().getHeaders()::set);
			return webSockets.handleRequest(exchange, session -> open(session, accepted));
		}
		return endpoint.handle(acp).flatMap(reply -> write(exchange.getResponse(), reply));
	}

	private static Mono<Void> open(WebSocketSession session, AcpWsHandshake.Accepted accepted) {
		Sinks.Many<String> frames = Sinks.many().unicast().onBackpressureBuffer();
		AcpWsHandler handler = accepted.open(new AcpWsOutbound() {

			@Override
			public CompletionStage<Void> sendText(String text) {
				// WebFlux sends from a Publisher with its own backpressure: no per-frame completion,
				// and the endpoint's one-at-a-time rule is met by the sink's serialization.
				Sinks.EmitResult result = frames.tryEmitNext(text);
				return result.isSuccess() ? CompletableFuture.completedFuture(null)
						: CompletableFuture.failedFuture(new IllegalStateException("socket closed: " + result));
			}

			@Override
			public void close(int code, String reason) {
				// Close with the endpoint's code first; completing the frames would end the send
				// and let WebFlux close the session without one (1005).
				session.close(CloseStatus.create(code, reason)).subscribe();
			}

		});
		Mono<Void> input = session.receive()
			.doOnNext(message -> handler.onText(message.getPayloadAsText()))
			.doOnError(handler::onError)
			.then();
		Mono<Void> output = session.send(frames.asFlux().map(session::textMessage));
		session.closeStatus()
			.defaultIfEmpty(CloseStatus.NO_STATUS_CODE)
			.subscribe(status -> handler.onClose(status.getCode(), status.getReason()));
		return Mono.zip(input, output).then();
	}

	private static Mono<Void> write(ServerHttpResponse response, AcpHttpReply reply) {
		response.setStatusCode(HttpStatus.valueOf(reply.status()));
		reply.headers().forEach(response.getHeaders()::set);
		if (reply instanceof AcpHttpReply.EventStream stream) {
			response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
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

	private static final class Exchange implements AcpHttpExchange {

		private final ServerWebExchange exchange;

		private final @Nullable Principal principal;

		Exchange(ServerWebExchange exchange, @Nullable Principal principal) {
			this.exchange = exchange;
			this.principal = principal;
		}

		@Override
		public String method() {
			return exchange.getRequest().getMethod().name().toUpperCase(Locale.ROOT);
		}

		@Override
		public @Nullable String header(String name) {
			return exchange.getRequest().getHeaders().getFirst(name);
		}

		@Override
		public Mono<byte[]> body(long maxBytes) {
			int limit = (int) Math.min(Integer.MAX_VALUE - 8, maxBytes);
			return DataBufferUtils.join(exchange.getRequest().getBody(), limit).map(buffer -> {
				byte[] bytes = new byte[buffer.readableByteCount()];
				buffer.read(bytes);
				DataBufferUtils.release(buffer);
				return bytes;
			})
				// Over the limit: one byte more than it tells the endpoint to answer 413.
				.onErrorResume(DataBufferLimitException.class, e -> Mono.just(new byte[limit + 1]))
				.defaultIfEmpty(new byte[0]);
		}

		@Override
		public @Nullable Principal principal() {
			return principal;
		}

	}

}
