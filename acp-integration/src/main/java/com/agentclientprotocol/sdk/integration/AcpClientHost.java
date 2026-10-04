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
 * Closes the client a framework built with {@link AcpClients} when the container disposes of
 * it: gracefully first, and at once if that takes too long. The framework creates one around its
 * {@link AcpAsyncClient} and calls {@link #close(Duration) close}{@code (settings.closeTimeout())}
 * from its dispose hook ({@link AcpClientSettings#closeTimeout()}). It is the client side's
 * counterpart of {@link AcpHost#stop(Duration)}; there is nothing to start, since building the
 * client connected it.
 *
 * <p>The graceful close is {@link AcpAsyncClient#closeGracefully()}: requests still waiting for
 * an answer fail, the agent's requests being handled are cancelled, session updates already
 * received still reach the consumers (bounded by the client's request timeout), and then the
 * transport closes. For stdio the transport closes the agent's input first, so the agent process
 * can exit by itself, and stops it if it does not. That one close covers the sync facade from
 * {@link AcpClients#sync} and the transport too, so the framework must not close either of them
 * again, for example through a destroy method the container infers from a {@code close()}
 * method on the bean. Only the first close of a host has an effect; create one host per client.
 * The host is safe for use from several threads.
 */
public final class AcpClientHost {

	private final AcpAsyncClient client;

	private final Object lock = new Object();

	private @Nullable CompletableFuture<@Nullable Void> closed;

	/**
	 * Creates a host for the client.
	 * @param client the async client from {@link AcpClients#async}
	 */
	public AcpClientHost(AcpAsyncClient client) {
		this.client = client;
	}

	/**
	 * Begins a graceful close of the client without waiting for it. Only the first call starts
	 * the close; every call returns the same stage.
	 * @return a stage that completes when the client is closed
	 */
	public CompletionStage<@Nullable Void> closeGracefully() {
		synchronized (lock) {
			CompletableFuture<@Nullable Void> current = closed;
			if (current == null) {
				current = client.closeGracefully().toFuture();
				closed = current;
			}
			return current;
		}
	}

	/**
	 * Closes the client: begins {@link #closeGracefully()} and waits for it at most
	 * {@code timeout}. If the graceful close fails, takes longer, or the waiting thread is
	 * interrupted (the interrupt is kept), it logs a warning and closes the client at once.
	 * Returns at once when the client is already closed.
	 * @param timeout how long to wait for the graceful close, usually
	 * {@link AcpClientSettings#closeTimeout()}
	 */
	public void close(Duration timeout) {
		Hosts.await(closeGracefully(), timeout, "ACP client", client::close);
	}

}
