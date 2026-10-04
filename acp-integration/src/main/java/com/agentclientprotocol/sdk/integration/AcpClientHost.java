/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import org.jspecify.annotations.Nullable;

/**
 * Closes the framework-built client, once: pending notifications are delivered (bounded by the
 * client's request timeout) and the transport is closed (for stdio, the agent's input first, so it
 * can exit by itself). The sync facade and the transport are not closed separately: the framework
 * must not also close them through an inferred destroy method.
 */
public final class AcpClientHost {

	private final AcpAsyncClient client;

	private final Object lock = new Object();

	private @Nullable CompletableFuture<Void> closed;

	/**
	 * A host for the client.
	 * @param client the async client
	 */
	public AcpClientHost(AcpAsyncClient client) {
		this.client = client;
	}

	/**
	 * Closes the client gracefully; only the first call has an effect.
	 * @return completes when the client is closed
	 */
	public CompletionStage<Void> closeGracefully() {
		synchronized (lock) {
			CompletableFuture<Void> current = closed;
			if (current == null) {
				current = client.closeGracefully().toFuture();
				closed = current;
			}
			return current;
		}
	}

	/**
	 * Closes the client gracefully, waiting at most {@code timeout}, then at once.
	 * @param timeout how long to wait, such as {@link AcpClientSettings#closeTimeout()}
	 */
	public void close(Duration timeout) {
		Hosts.await(closeGracefully(), timeout, "ACP client", client::close);
	}

}
