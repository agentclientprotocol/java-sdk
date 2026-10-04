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
 * One agent on one transport: stdio, or an application's own transport. Starting, stopping and
 * the end of the transport are the SDK's own lifecycle: {@link AcpAgentSupport#start()}, its
 * {@link AcpAgentSupport#close() AutoCloseable close} (graceful, bounded, then at once) and its
 * agent's {@code awaitTermination()}.
 *
 * <p>
 * When the transport ends by itself (for stdio: the client closed the agent's input and every
 * answer has been written), the agent has no one left to serve, and the host runs the
 * {@code onTransportEnd} action the framework gave it, typically "close my container". That
 * action is latched: it runs at most once, on a thread of the host's own (never the transport's,
 * since closing the container stops this host, which closes the transport), also when the
 * transport ended before anyone looked, and never when the host itself stopped the agent.
 * </p>
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
	 * A host for an agent built on its transport.
	 * @param agent the agent
	 * @param onTransportEnd what to do when the transport ends by itself, such as closing the
	 * container; fixed here, so no end is missed
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
