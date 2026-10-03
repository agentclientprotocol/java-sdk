/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.util.OptionalInt;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import reactor.core.publisher.Mono;

/** One annotated agent on one transport: stdio, or an application's own transport bean. */
final class SingleTransportHost implements AgentHost {

	private final AcpAgentSupport agent;

	private final AcpAgentTransport transport;

	SingleTransportHost(AcpAgentSupport.Builder builder, AcpAgentTransport transport) {
		this.agent = builder.transport(transport).build();
		this.transport = transport;
	}

	@Override
	public void start() {
		agent.start();
	}

	@Override
	public Mono<Void> awaitTermination() {
		return transport.awaitTermination();
	}

	@Override
	public Mono<Void> closeGracefully() {
		// The sync agent's close blocks, bounded by its own timeout, and closes the transport.
		return Mono.fromRunnable(agent::close);
	}

	@Override
	public OptionalInt port() {
		return OptionalInt.empty();
	}

	@Override
	public boolean endsWithItsClient() {
		return true;
	}

}
