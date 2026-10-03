/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.util.OptionalInt;

import reactor.core.publisher.Mono;

/**
 * What {@link AcpAgentRuntime} runs: one agent on one transport, or a listener serving one
 * agent per connection.
 */
interface AgentHost {

	/** Starts serving; returns once the transport or listener has started. */
	void start();

	/**
	 * Completes when the transport or listener has ended, by itself or by
	 * {@link #closeGracefully()}.
	 * @return the termination signal
	 */
	Mono<Void> awaitTermination();

	/**
	 * Closes gracefully; a second call completes at once.
	 * @return completes when closed
	 */
	Mono<Void> closeGracefully();

	/**
	 * The port the listener is bound to.
	 * @return the port, or empty for a transport that has none
	 */
	OptionalInt port();

	/** Whether ending this host's transport by itself means the application is done. */
	boolean endsWithItsClient();

}
