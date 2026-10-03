/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.function.Function;

import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.util.Assert;

/**
 * Builds one agent for each connection a listener transport accepts. Give it to
 * {@code StreamableHttpAcpAgentTransport} or {@code StreamableHttpAcpServlet} (module
 * {@code acp-streamable-http-jetty}), which serve many clients at once: each new connection gets
 * its own transport and calls {@link #create(AcpAgentTransport)} for an agent bound to it. A stdio
 * agent serves a single connection and needs no factory.
 *
 * <p>Make one from an {@link AcpAgent} builder call with {@link #sync(Function)} or
 * {@link #async(Function)}. For an annotated agent, {@code AcpAgentSupport.Builder.buildFactory()}
 * (module {@code acp-agent-support}) returns one.
 *
 * <pre>{@code
 * AcpAgentFactory agents = AcpAgentFactory.sync(transport -> AcpAgent.sync(transport)
 *     .initializeHandler(request -> AcpSchema.InitializeResponse.ok())
 *     .newSessionHandler(request -> new AcpSchema.NewSessionResponse(
 *         UUID.randomUUID().toString(), null, null))
 *     .promptHandler((request, context) -> AcpSchema.PromptResponse.endTurn())
 *     .build());
 * }</pre>
 *
 * <p>Implementations return a new agent built on the given transport and leave it unstarted: the
 * connection starts it, and closes it when the connection closes. Connections can open at the same
 * time, so {@code create} may be called from several threads at once, and anything the agents share
 * must be thread-safe.
 *
 * @author Kaiser Dandangi
 */
@FunctionalInterface
public interface AcpAgentFactory {

	/**
	 * Creates the agent for one connection.
	 * @param transport the connection's transport; build the agent on this one
	 * @return a new agent, not started
	 */
	AcpAsyncAgent create(AcpAgentTransport transport);

	/**
	 * Returns a factory that calls the given function for each connection.
	 * @param factory builds a new agent on the transport it is given, typically
	 * {@code transport -> AcpAgent.async(transport)...build()}
	 * @return the factory
	 * @throws IllegalArgumentException if {@code factory} is null
	 */
	static AcpAgentFactory async(Function<AcpAgentTransport, AcpAsyncAgent> factory) {
		Assert.notNull(factory, "The async factory can not be null");
		return factory::apply;
	}

	/**
	 * Returns a factory that calls the given function for each connection and hands the connection
	 * the asynchronous agent behind the synchronous one ({@link AcpSyncAgent#async()}). The
	 * handlers still run as the synchronous builder runs them, on
	 * {@link AcpAgent#SYNC_HANDLER_SCHEDULER}.
	 * @param factory builds a new agent on the transport it is given, typically
	 * {@code transport -> AcpAgent.sync(transport)...build()}
	 * @return the factory
	 * @throws IllegalArgumentException if {@code factory} is null
	 */
	static AcpAgentFactory sync(Function<AcpAgentTransport, AcpSyncAgent> factory) {
		Assert.notNull(factory, "The sync factory can not be null");
		return transport -> factory.apply(transport).async();
	}

}
