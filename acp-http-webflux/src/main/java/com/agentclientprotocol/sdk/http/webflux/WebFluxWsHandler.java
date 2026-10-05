/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.webflux;

import com.agentclientprotocol.sdk.http.server.AcpWsHandler;
import com.agentclientprotocol.sdk.http.server.AcpWsHandshake;
import reactor.core.publisher.Mono;

import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * One accepted WebSocket: opens the endpoint's connection on the session and carries its
 * events. Text messages arrive whole (WebFlux aggregates fragments) and go to the endpoint, the
 * endpoint's frames go out through {@link WebFluxWsOutbound}, and the close the session reports
 * goes to the endpoint, 1006 when the connection dropped without one. A message over the socket's
 * own limit (on Reactor Netty, the endpoint's limit plus one) closes the socket with 1009, as
 * {@link AcpWsHandshake.Accepted#maxTextMessageBytes()} has it; any other socket failure goes
 * to the endpoint.
 *
 * @author Mark Pollack
 */
final class WebFluxWsHandler implements WebSocketHandler {

	/** The close code for a connection that dropped without a close frame. */
	static final int ABNORMAL_CLOSURE = 1006;

	/** The close code for a message over the socket's limit. */
	static final int MESSAGE_TOO_BIG = 1009;

	private final AcpWsHandshake.Accepted accepted;

	WebFluxWsHandler(AcpWsHandshake.Accepted accepted) {
		this.accepted = accepted;
	}

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		WebFluxWsOutbound outbound = new WebFluxWsOutbound(session);
		AcpWsHandler handler = accepted.open(outbound);
		session.closeStatus()
			.defaultIfEmpty(CloseStatus.create(ABNORMAL_CLOSURE, "connection dropped"))
			.onErrorResume(error -> Mono.just(CloseStatus.create(ABNORMAL_CLOSURE, "connection dropped")))
			.subscribe(status -> handler.onClose(status.getCode(), status.getReason()));
		Mono<Void> input = session.receive()
			.doOnNext(message -> {
				if (message.getType() == WebSocketMessage.Type.TEXT) {
					handler.onText(message.getPayloadAsText());
				}
			})
			.onErrorResume(error -> {
				if (WebSocketUpgrades.isMessageTooBig(error)) {
					// Over the socket's own limit, which is above the endpoint's: the contract's
					// close for a message the socket refuses.
					outbound.close(MESSAGE_TOO_BIG, "message too big");
				}
				else {
					handler.onError(error);
				}
				return Mono.empty();
			})
			.doFinally(signal -> outbound.closed())
			.then();
		Mono<Void> output = session.send(outbound.frames().map(session::textMessage))
			.onErrorResume(error -> {
				handler.onError(error);
				return Mono.empty();
			});
		return Mono.when(input, output);
	}

}
