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
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * One agent on one transport: stdio, or an application's own transport. Starting and stopping use
 * the SDK's own lifecycle ({@link AcpAgentSupport#start()} and {@link AcpAgentSupport#close()}).
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

	private final CompletableFuture<Void> termination;

	private final Object lock = new Object();

	private final AtomicBoolean stopping = new AtomicBoolean();

	private final AtomicBoolean transportEndHandled = new AtomicBoolean();

	private boolean started;

	private @Nullable CompletableFuture<Void> stopped;

	/**
	 * A host for an agent built on {@code transport}.
	 * @param agent the agent, built on {@code transport}
	 * @param transport the agent's transport
	 * @param onTransportEnd what to do when the transport ends by itself, such as closing the
	 * container; fixed here, so no end is missed
	 */
	public AcpAgentHost(AcpAgentSupport agent, AcpAgentTransport transport, Runnable onTransportEnd) {
		this.agent = agent;
		this.onTransportEnd = onTransportEnd;
		this.termination = transport.awaitTermination().onErrorResume(error -> {
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
	public CompletionStage<Void> stopGracefully() {
		synchronized (lock) {
			CompletableFuture<Void> current = stopped;
			if (current == null) {
				stopping.set(true);
				// Off the caller's thread: the SDK's close blocks, bounded by its own timeout.
				current = CompletableFuture.runAsync(agent::close, runnable -> {
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
		Hosts.await(stopGracefully(), timeout, "ACP agent", () -> agent.getAgent().close());
	}

	@Override
	public CompletionStage<Void> termination() {
		return termination.minimalCompletionStage();
	}

	@Override
	public OptionalInt port() {
		return OptionalInt.empty();
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
