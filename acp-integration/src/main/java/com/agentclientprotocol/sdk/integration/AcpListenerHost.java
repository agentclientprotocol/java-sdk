/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SDK's Streamable HTTP listener ({@code acp-streamable-http-jetty}, on its own port; HTTP/1.1,
 * cleartext HTTP/2 and WebSocket upgrades on one path), serving one agent per connection. It ends
 * only when stopped: a listener serves many clients, so no client leaving ends it. Create the
 * listener with {@link AcpListeners#listener}.
 */
public final class AcpListenerHost implements AcpHost {

	private static final Logger logger = LoggerFactory.getLogger(AcpListenerHost.class);

	/** How long starting the listener may take. */
	static final Duration START_TIMEOUT = Duration.ofSeconds(30);

	private final StreamableHttpAcpAgentTransport listener;

	private final CompletableFuture<Void> termination;

	private final Object lock = new Object();

	private boolean started;

	private @Nullable CompletableFuture<Void> stopped;

	/**
	 * A host for the listener.
	 * @param listener the listener, not started
	 */
	public AcpListenerHost(StreamableHttpAcpAgentTransport listener) {
		this.listener = listener;
		this.termination = listener.awaitTermination().toFuture();
	}

	@Override
	public void start() {
		synchronized (lock) {
			if (started || stopped != null) {
				return;
			}
			listener.start().block(START_TIMEOUT);
			started = true;
			logger.info("ACP agent listening on port {}", listener.getPort());
		}
	}

	@Override
	public CompletionStage<Void> stopGracefully() {
		synchronized (lock) {
			CompletableFuture<Void> current = stopped;
			if (current == null) {
				// The listener's close is bounded by its shutdown timeout.
				current = listener.closeGracefully().toFuture();
				stopped = current;
			}
			return current;
		}
	}

	@Override
	public void stop(Duration timeout) {
		Hosts.await(stopGracefully(), timeout, "ACP agent listener", () -> {
			// Nothing closes faster than the listener's own bounded close.
		});
	}

	@Override
	public CompletionStage<Void> termination() {
		return termination.minimalCompletionStage();
	}

	@Override
	public OptionalInt port() {
		synchronized (lock) {
			return started ? OptionalInt.of(listener.getPort()) : OptionalInt.empty();
		}
	}

}
