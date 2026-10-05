/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.webflux;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * A WebSocket session whose client the test plays: it pushes inbound text, decides how many
 * outbound frames WebFlux asks for, and sees the close.
 */
final class FakeWebSocketSession implements WebSocketSession {

	private final DataBufferFactory buffers = DefaultDataBufferFactory.sharedInstance;

	private final Sinks.Many<WebSocketMessage> inbound = Sinks.many().unicast().onBackpressureBuffer();

	private final Sinks.One<CloseStatus> closeStatus = Sinks.one();

	final List<String> sent = new CopyOnWriteArrayList<>();

	final List<CloseStatus> closes = new CopyOnWriteArrayList<>();

	private final long initialDemand;

	private volatile @Nullable Subscription sending;

	FakeWebSocketSession(long initialDemand) {
		this.initialDemand = initialDemand;
	}

	/** The client sends a text frame. */
	void receive(String text) {
		inbound.tryEmitNext(textMessage(text)).orThrow();
	}

	/** The client closes the socket. */
	void clientCloses(int code) {
		closeStatus.tryEmitValue(new CloseStatus(code)).orThrow();
		inbound.tryEmitComplete().orThrow();
	}

	/** WebFlux asks for more frames, as when the client's socket drains. */
	void request(long n) {
		Subscription current = sending;
		if (current == null) {
			throw new IllegalStateException("nothing is sending");
		}
		current.request(n);
	}

	@Override
	public String getId() {
		return "fake";
	}

	@Override
	public HandshakeInfo getHandshakeInfo() {
		return new HandshakeInfo(URI.create("ws://127.0.0.1/acp"), new HttpHeaders(), Mono.empty(), null);
	}

	@Override
	public DataBufferFactory bufferFactory() {
		return buffers;
	}

	@Override
	public Map<String, Object> getAttributes() {
		return new ConcurrentHashMap<>();
	}

	@Override
	public Flux<WebSocketMessage> receive() {
		return inbound.asFlux();
	}

	@Override
	public Mono<Void> send(Publisher<WebSocketMessage> messages) {
		return Mono.create(done -> {
			BaseSubscriber<WebSocketMessage> subscriber = new BaseSubscriber<>() {

			@Override
			protected void hookOnSubscribe(Subscription subscription) {
				sending = subscription;
				if (initialDemand > 0) {
					subscription.request(initialDemand);
				}
			}

			@Override
			protected void hookOnNext(WebSocketMessage message) {
				sent.add(message.getPayloadAsText());
			}

			@Override
			protected void hookOnComplete() {
				done.success();
			}

			@Override
			protected void hookOnError(Throwable error) {
				done.error(error);
			}

			};
			done.onCancel(subscriber);
			messages.subscribe(subscriber);
		});
	}

	@Override
	public boolean isOpen() {
		return closes.isEmpty();
	}

	@Override
	public Mono<Void> close(CloseStatus status) {
		return Mono.fromRunnable(() -> {
			closes.add(status);
			closeStatus.tryEmitValue(status);
			inbound.tryEmitComplete();
		});
	}

	@Override
	public Mono<CloseStatus> closeStatus() {
		return closeStatus.asMono();
	}

	@Override
	public WebSocketMessage textMessage(String payload) {
		return new WebSocketMessage(WebSocketMessage.Type.TEXT, buffers.wrap(payload.getBytes(StandardCharsets.UTF_8)));
	}

	@Override
	public WebSocketMessage binaryMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
		return new WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(buffers));
	}

	@Override
	public WebSocketMessage pingMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
		return new WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(buffers));
	}

	@Override
	public WebSocketMessage pongMessage(Function<DataBufferFactory, DataBuffer> payloadFactory) {
		return new WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(buffers));
	}

}
