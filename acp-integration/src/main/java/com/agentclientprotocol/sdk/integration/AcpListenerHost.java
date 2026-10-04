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
 * The {@link AcpHost} for the SDK's own Streamable HTTP listener from
 * {@code acp-streamable-http-jetty}: a Jetty server on its own port that serves HTTP/1.1,
 * cleartext HTTP/2 and WebSocket upgrades on one path, with one agent per connection. Use it when
 * the agent is served over {@code http} or {@code websocket} and the framework has no Servlet
 * container to mount the SDK's servlet in (then see {@link AcpServletHost}). Create the listener
 * with {@link AcpListeners#listener}, after checking {@link AcpListeners#isListenerAvailable()}.
 * For one agent on stdio, use {@link AcpAgentHost}.
 *
 * <p>How it differs from {@link AcpAgentHost}: there is no transport-end action, because the
 * listener ends only when stopped; a listener serves many clients, so no client leaving ends it.
 * Nor is {@link #holdJvmUntilTermination()} needed: Jetty's threads are not daemons and keep the
 * JVM running until the stop. {@link #start()} binds the port and waits for it at most 30
 * seconds, holding the host's lock meanwhile, so a stop that arrives during the start waits for
 * the start to return. {@link #port()} then gives the bound port, the way to learn the port
 * chosen for port 0. A stop closes every connection as
 * {@code StreamableHttpAcpAgentTransport.closeGracefully()} does, bounded by the endpoint's
 * shutdown timeout (5 seconds unless {@link AcpAgentSettings.Limits#shutdownTimeout()} sets one).
 * {@link #stop(Duration)} has nothing faster to fall back on: when its timeout passes, it returns
 * while that close goes on. {@link #termination()} completes when the listener has stopped.
 *
 * <p>The host is safe for use from several threads.
 */
public final class AcpListenerHost implements AcpHost {

	private static final Logger logger = LoggerFactory.getLogger(AcpListenerHost.class);

	/** How long starting the listener may take. */
	static final Duration START_TIMEOUT = Duration.ofSeconds(30);

	private final StreamableHttpAcpAgentTransport listener;

	private final CompletableFuture<@Nullable Void> termination;

	private final Object lock = new Object();

	private boolean started;

	private @Nullable CompletableFuture<@Nullable Void> stopped;

	/**
	 * Creates a host for the listener, not yet started.
	 * @param listener the listener from {@link AcpListeners#listener}, not started
	 */
	public AcpListenerHost(StreamableHttpAcpAgentTransport listener) {
		this.listener = listener;
		this.termination = listener.awaitTermination().toFuture();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Binds the listener's port, waiting at most 30 seconds, and logs the port at INFO. A
	 * listener whose start failed cannot be started again.
	 * @throws IllegalStateException if the listener does not start within 30 seconds
	 * @throws RuntimeException if the server cannot start, for example because the port is in
	 * use; a checked cause such as a {@code BindException} arrives wrapped
	 */
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
	public CompletionStage<@Nullable Void> stopGracefully() {
		synchronized (lock) {
			CompletableFuture<@Nullable Void> current = stopped;
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
	public CompletionStage<@Nullable Void> termination() {
		return termination.minimalCompletionStage();
	}

	@Override
	public OptionalInt port() {
		synchronized (lock) {
			return started ? OptionalInt.of(listener.getPort()) : OptionalInt.empty();
		}
	}

}
