/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.jspecify.annotations.Nullable;

/**
 * Runs the application's {@code @AcpAgent} for a framework: the framework calls {@link #start()}
 * from its start hook and {@link #stop(Duration)} from its stop hook (or a JVM shutdown hook), and
 * the host does the rest. Use {@link AcpAgentHost} for one agent on one transport (stdio, or a
 * transport bean of the application's) and {@link AcpListenerHost} for the SDK's own HTTP and
 * WebSocket listener. An agent served by the framework's own Servlet container needs no host; see
 * {@link AcpServletHost}.
 *
 * <p>Only {@link #stop(Duration)} blocks on purpose: {@code start()} returns once the transport
 * or listener runs, and the SDK serves the agent on threads of its own. So a framework whose
 * {@code main} returns once its context is up (Micronaut does) can host an agent too. The SDK's
 * threads are daemons, so such a framework calls {@link #holdJvmUntilTermination()} after
 * {@code start()} when nothing else keeps the JVM running (for stdio, without an embedded
 * server).
 *
 * <p>The lifecycle is one-way: {@code start()} once, then stop. A second {@code start()} does
 * nothing, and so does a {@code start()} after a stop began; the stop methods share one graceful
 * stop, however often they are called. {@link #termination()} completes when the transport or
 * listener has ended, whether a stop ended it or (for stdio) the client did.
 *
 * <p>Implementations must be safe to call from several threads at once, since a shutdown hook
 * may stop the host while the framework's start hook is still starting it.
 * {@link #holdJvmUntilTermination()} has a default built on {@link #termination()}.
 *
 * @see AcpAgentHost
 * @see AcpListenerHost
 */
public interface AcpHost {

	/** The name of the thread {@link #holdJvmUntilTermination()} starts: {@value}. */
	String HOLD_THREAD_NAME = "acp-agent-await";

	/**
	 * Starts serving the agent and returns once the transport or listener runs. Does nothing if
	 * the host was started before, or once a stop has begun.
	 * @throws RuntimeException if the transport or listener cannot start; each implementation
	 * names the cases
	 */
	void start();

	/**
	 * Begins a graceful stop without waiting for it: requests in flight are answered or
	 * cancelled within the SDK's bounds, then the transport or listener closes. Only the first
	 * call starts the stop; every call returns the same stage. Calling it before {@link #start()}
	 * stops the host for good. A framework with an asynchronous graceful-shutdown hook returns
	 * this stage from it.
	 * @return a stage that completes when the host has stopped
	 */
	CompletionStage<@Nullable Void> stopGracefully();

	/**
	 * Stops the host: begins {@link #stopGracefully()} and waits for it at most {@code timeout}.
	 * If the graceful stop fails, takes longer, or the waiting thread is interrupted (the
	 * interrupt is kept), the host stops waiting and closes what it can at once: each
	 * implementation says what that is. Call it from the framework's stop hook or a JVM shutdown
	 * hook; it is safe while {@link #start()} is still running on another thread. Returns at once
	 * when the host has already stopped.
	 * @param timeout how long to wait for the graceful stop
	 */
	void stop(Duration timeout);

	/**
	 * Returns a stage that completes when the transport or listener has ended: by a stop, or, for
	 * a single transport such as stdio, by the client closing it. The stage is read-only.
	 * @return the termination stage
	 */
	CompletionStage<@Nullable Void> termination();

	/**
	 * Returns the port the SDK's listener is bound to, which is how a framework learns the port
	 * the operating system chose for port 0.
	 * @return the bound port once started; empty before the start, and always empty for a host
	 * of a single transport such as stdio
	 */
	OptionalInt port();

	/**
	 * Starts one non-daemon thread, named {@value #HOLD_THREAD_NAME}, that ends when
	 * {@link #termination()} completes, normally or with an error. The SDK's own threads are
	 * daemons, so a framework calls this after {@link #start()} when nothing else keeps the JVM
	 * running: for example a stdio agent in an application without an embedded server. Call it
	 * once; each call starts another thread.
	 *
	 * @implSpec The default starts the thread and returns at once; the thread joins
	 * {@code termination()} and ignores how it completed.
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
