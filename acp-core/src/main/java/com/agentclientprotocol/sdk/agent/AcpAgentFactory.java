/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.function.Function;

import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.util.Assert;

/**
 * Factory for creating one ACP agent runtime for one agent-side transport.
 *
 * <p>
 * Listener-backed transports such as remote HTTP transports accept multiple client
 * connections over their lifetime. Each accepted connection needs its own
 * connection-bound agent runtime while reusing the same agent definition. This factory
 * is the explicit public seam for that relationship.
 * </p>
 *
 * @author Kaiser Dandangi
 */
@FunctionalInterface
public interface AcpAgentFactory {

	/**
	 * Creates a new asynchronous agent runtime for the supplied transport.
	 * @param transport per-connection transport
	 * @return a fresh asynchronous agent runtime
	 */
	AcpAsyncAgent create(AcpAgentTransport transport);

	/**
	 * Creates a factory from an asynchronous agent builder function.
	 * @param factory function that creates a fresh asynchronous agent per transport
	 * @return an agent factory
	 */
	static AcpAgentFactory async(Function<AcpAgentTransport, AcpAsyncAgent> factory) {
		Assert.notNull(factory, "The async factory can not be null");
		return factory::apply;
	}

	/**
	 * Creates a factory from a synchronous agent builder function.
	 *
	 * <p>
	 * Synchronous agents are wrappers around asynchronous agents in this SDK, so the
	 * transport seam remains asynchronous underneath while callers may still author
	 * agents with the blocking API.
	 * </p>
	 * @param factory function that creates a fresh synchronous agent per transport
	 * @return an agent factory
	 */
	static AcpAgentFactory sync(Function<AcpAgentTransport, AcpSyncAgent> factory) {
		Assert.notNull(factory, "The sync factory can not be null");
		return transport -> factory.apply(transport).async();
	}

}
