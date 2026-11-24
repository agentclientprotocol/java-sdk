/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import reactor.core.publisher.Mono;

/**
 * The in-process transport pair under its former test-kit name. It delegates to
 * {@link com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair} in acp-core, the
 * supported in-process transport, and behaves exactly as it does: see that class for delivery and
 * for the end of the connection, which either side's close now ends for both. Use that class
 * instead, in applications and tests alike; only the import changes.
 *
 * @author Mark Pollack
 * @deprecated since 0.82.0, for removal in a later release: use
 * {@link com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair} from acp-core.
 */
@Deprecated(since = "0.82.0")
public class InMemoryTransportPair {

	private final com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair delegate;

	private InMemoryTransportPair() {
		this.delegate = com.agentclientprotocol.sdk.agent.transport.InMemoryTransportPair.create();
	}

	/**
	 * Creates a pair of connected transports, neither of them connected or started yet.
	 * @return a new pair
	 */
	public static InMemoryTransportPair create() {
		return new InMemoryTransportPair();
	}

	/**
	 * Returns the client side of the pair; it can be connected once.
	 * @return the client transport, the same instance on every call
	 */
	public AcpClientTransport clientTransport() {
		return this.delegate.clientTransport();
	}

	/**
	 * Returns the agent side of the pair; it can be started once.
	 * @return the agent transport, the same instance on every call
	 */
	public AcpAgentTransport agentTransport() {
		return this.delegate.agentTransport();
	}

	/**
	 * Ends the connection for both sides; it can be called more than once.
	 * @return a {@code Mono} that completes when the connection has ended
	 */
	public Mono<Void> closeGracefully() {
		return this.delegate.closeGracefully();
	}

}
