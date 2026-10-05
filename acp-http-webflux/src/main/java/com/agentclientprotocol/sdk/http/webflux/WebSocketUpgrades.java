/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.webflux;

import com.agentclientprotocol.sdk.http.server.AcpWsHandshake;

import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import org.springframework.web.server.ServerWebExchange;

/**
 * WebFlux's WebSocket support for one accepted handshake, with the socket's message limit
 * above the endpoint's where the server takes one per upgrade. On Reactor Netty the frame limit
 * (64 KB by default) becomes the endpoint's limit plus one, so an oversized message reaches the
 * endpoint, which closes with 1009, rather than failing Netty's decoder. Other servers keep
 * their WebSocket container's own limit. Reactor Netty's types load only when it is the server.
 *
 * @author Mark Pollack
 */
final class WebSocketUpgrades {

	private static final boolean REACTOR_NETTY_PRESENT = isPresent("reactor.netty.http.server.HttpServerResponse");

	private WebSocketUpgrades() {
	}

	static HandshakeWebSocketService service(ServerWebExchange exchange, AcpWsHandshake.Accepted accepted) {
		if (REACTOR_NETTY_PRESENT && Netty.serves(exchange)) {
			return Netty.service(accepted);
		}
		return new HandshakeWebSocketService();
	}

	/**
	 * Returns whether a socket failed on a message over its limit: on Reactor Netty, an
	 * aggregated message or a single frame too long.
	 */
	static boolean isMessageTooBig(Throwable error) {
		return REACTOR_NETTY_PRESENT && Netty.isMessageTooBig(error);
	}

	/** The frame limit for an endpoint limit: one byte more, within an int. */
	static int frameLimit(long maxTextMessageBytes) {
		return (int) Math.min(Integer.MAX_VALUE, maxTextMessageBytes + 1);
	}

	private static boolean isPresent(String className) {
		try {
			Class.forName(className, false, WebSocketUpgrades.class.getClassLoader());
			return true;
		}
		catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	/** Holds the Reactor Netty types, so they load only once Reactor Netty is known present. */
	private static final class Netty {

		static boolean serves(ServerWebExchange exchange) {
			try {
				return ServerHttpResponseDecorator.getNativeResponse(
						exchange.getResponse()) instanceof reactor.netty.http.server.HttpServerResponse;
			}
			catch (IllegalArgumentException e) {
				// A response with no native one underneath, such as a test's mock.
				return false;
			}
		}

		static boolean isMessageTooBig(Throwable error) {
			if (error instanceof io.netty.handler.codec.TooLongFrameException) {
				return true;
			}
			return error instanceof io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException corrupted
					&& corrupted.closeStatus().code() == WebFluxWsHandler.MESSAGE_TOO_BIG;
		}

		static HandshakeWebSocketService service(AcpWsHandshake.Accepted accepted) {
			int limit = frameLimit(accepted.maxTextMessageBytes());
			return new HandshakeWebSocketService(new ReactorNettyRequestUpgradeStrategy(
					() -> reactor.netty.http.server.WebsocketServerSpec.builder().maxFramePayloadLength(limit)));
		}

	}

}
