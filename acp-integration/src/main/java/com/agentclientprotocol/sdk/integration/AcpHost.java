/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Serves an agent for a framework, which calls these methods from its own lifecycle hooks: start
 * with the container, stop with it. Nothing here blocks except {@link #stop(Duration)}, so a
 * framework whose {@code main} returns (Micronaut, for one) can host an agent too.
 *
 * @see AcpAgentHost
 * @see AcpListenerHost
 */
public interface AcpHost {

	/** The name of the thread {@link #holdJvmUntilTermination()} starts. */
	String HOLD_THREAD_NAME = "acp-agent-await";

	/**
	 * Starts serving; returns once the transport or listener has started. Does nothing once
	 * started, or once stopping.
	 */
	void start();

	/**
	 * Stops gracefully: in-flight requests are answered or cancelled within the SDK's bounds, and
	 * the transport closes. Only the first call has an effect; later calls return the same stage.
	 * @return completes when stopped
	 */
	CompletionStage<Void> stopGracefully();

	/**
	 * Stops gracefully, waiting at most {@code timeout}, then stops at once. Safe from a JVM
	 * shutdown hook, also while {@link #start()} is still running on another thread.
	 * @param timeout how long to wait for the graceful stop
	 */
	void stop(Duration timeout);

	/**
	 * Completes when the transport or listener ends, by itself or by a stop.
	 * @return the termination signal
	 */
	CompletionStage<Void> termination();

	/**
	 * The port a listener is bound to, once started.
	 * @return the port, or empty for a single transport (stdio) or before the start
	 */
	OptionalInt port();

	/**
	 * Starts one non-daemon thread, named {@value #HOLD_THREAD_NAME}, that lives until
	 * {@link #termination()}. For an application with nothing else keeping the JVM up: the SDK's
	 * own threads are daemons.
	 */
	default void holdJvmUntilTermination() {
		Thread hold = new Thread(() -> {
			try {
				termination().toCompletableFuture().join();
			}
			catch (CompletionException ex) {
				// The transport ended with an error: it has ended all the same.
			}
		}, HOLD_THREAD_NAME);
		hold.setDaemon(false);
		hold.start();
	}

}
