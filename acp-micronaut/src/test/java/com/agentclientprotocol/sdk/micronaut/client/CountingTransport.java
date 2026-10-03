/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/** A client transport that counts its graceful closes and delegates everything. */
final class CountingTransport implements AcpClientTransport {

	private final AcpClientTransport delegate;

	private final AtomicInteger closes;

	CountingTransport(AcpClientTransport delegate, AtomicInteger closes) {
		this.delegate = delegate;
		this.closes = closes;
	}

	@Override
	public Mono<Void> connect(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
		return delegate.connect(handler);
	}

	@Override
	public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
		return delegate.sendMessage(message);
	}

	@Override
	public Mono<Void> closeGracefully() {
		closes.incrementAndGet();
		return delegate.closeGracefully();
	}

	@Override
	public void close() {
		delegate.close();
	}

	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		delegate.setExceptionHandler(handler);
	}

	@Override
	public Mono<Void> awaitTermination() {
		return delegate.awaitTermination();
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return delegate.unmarshalFrom(data, typeRef);
	}

	@Override
	public List<Integer> protocolVersions() {
		return delegate.protocolVersions();
	}

}
