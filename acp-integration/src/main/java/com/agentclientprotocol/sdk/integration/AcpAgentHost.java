/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The {@link AcpHost} for one agent on one transport: stdio, or a transport bean the application
 * provides (an in-memory transport in tests, for one). Build the agent with
 * {@link AcpAgents#builder}, set its transport ({@link AcpAgentTransports#stdio()}), build it,
 * and give it to this host with the action to run when the client ends the transport. For the
 * SDK's HTTP and WebSocket listener, which serves many clients, use {@link AcpListenerHost}.
 *
 * <p>The host drives the SDK's own lifecycle: {@link #start()} calls
 * {@link AcpAgentSupport#start()}; a stop runs {@link AcpAgentSupport#close()} (graceful, at most
 * about 10 seconds, then at once) on a daemon thread named {@code acp-agent-stop}, and
 * {@link #stop(Duration)} closes the agent at once when that does not finish within its timeout.
 * {@link #termination()} follows the agent's {@code awaitTermination()}, and completes normally
 * also when the transport ended with an error. {@link #port()} is always empty.
 *
 * <p>When the transport ends by itself (for stdio: the client closed the agent's input and
 * every answer has been written), the agent has no one left to serve, and the host runs
 * {@code onTransportEnd}, typically "close my container" or "exit the application". The host
 * runs it at most once, on a new non-daemon thread named {@code acp-agent-transport-end}, never
 * on a transport thread: closing the container stops this host, which closes the transport, and
 * the JVM waits for the action to finish. It also runs when the transport ended before the host
 * started or saw it, and it does not run when the host's own stop ended the transport. The
 * host itself never reads {@link AcpAgentSettings#shutdownOnTransportEnd()}: when that setting
 * is false, the framework passes an action that does nothing.
 *
 * <p>The host is safe for use from several threads; {@link #start()} and the stop methods
 * share one lock.
 */
public final class AcpAgentHost implements AcpHost {

	private static final Logger logger = LoggerFactory.getLogger(AcpAgentHost.class);

	/** The name of the thread that runs the transport-end action. */
	static final String TRANSPORT_END_THREAD_NAME = "acp-agent-transport-end";

	private final AcpAgentSupport agent;

	private final Runnable onTransportEnd;

	private final CompletableFuture<@Nullable Void> termination;

	private final Object lock = new Object();

	private final AtomicBoolean stopping = new AtomicBoolean();

	private final AtomicBoolean transportEndHandled = new AtomicBoolean();

	private boolean started;

	private @Nullable CompletableFuture<@Nullable Void> stopped;

	/**
	 * Creates a host for an agent built on its transport, not yet started. The host watches the
	 * agent's termination from here on, so the action is fixed now and no end of the transport is
	 * missed, even one before {@link #start()}.
	 * @param agent the agent, built with its transport and not started
	 * @param onTransportEnd what to run once when the transport ends by itself, such as closing
	 * the container; an action that does nothing when the application should keep running
	 */
	public AcpAgentHost(AcpAgentSupport agent, Runnable onTransportEnd) {
		this.agent = agent;
		this.onTransportEnd = onTransportEnd;
		this.termination = agent.getAgent().async().awaitTermination().onErrorResume(error -> {
			logger.debug("ACP agent transport ended with an error: {}", error.toString());
			return Mono.empty();
		}).doOnSuccess(ignored -> transportEnded()).toFuture();
	}

	@Override
	public void start() {
		synchronized (lock) {
			if (started || stopping.get()) {
				return;
			}
			started = true;
			agent.start();
		}
	}

	@Override
	public CompletionStage<@Nullable Void> stopGracefully() {
		synchronized (lock) {
			CompletableFuture<@Nullable Void> current = stopped;
			if (current == null) {
				stopping.set(true);
				// Off the caller's thread: the SDK's close blocks, gracefully for a bounded time,
				// then closes at once.
				current = CompletableFuture.<@Nullable Void>supplyAsync(this::closeAgent, runnable -> {
					Thread closer = new Thread(runnable, "acp-agent-stop");
					closer.setDaemon(true);
					closer.start();
				});
				stopped = current;
			}
			return current;
		}
	}

	@Override
	public void stop(Duration timeout) {
		Hosts.await(stopGracefully(), timeout, "ACP agent", () -> agent.getAgent().async().close());
	}

	@Override
	public CompletionStage<@Nullable Void> termination() {
		return termination.minimalCompletionStage();
	}

	@Override
	public OptionalInt port() {
		return OptionalInt.empty();
	}

	private @Nullable Void closeAgent() {
		agent.close();
		return null;
	}

	private void transportEnded() {
		if (stopping.get() || !transportEndHandled.compareAndSet(false, true)) {
			return;
		}
		Thread action = new Thread(() -> {
			if (!stopping.get()) {
				logger.debug("ACP agent transport ended");
				onTransportEnd.run();
			}
		}, TRANSPORT_END_THREAD_NAME);
		// Not a daemon: the action (closing the container) must finish before the JVM exits.
		action.setDaemon(false);
		action.start();
	}

}
