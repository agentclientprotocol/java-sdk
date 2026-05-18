/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class AcpAgentFactoryTest {

	@Test
	void asyncFactoryReturnsFreshAgentRuntime() {
		AcpAgentFactory factory = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
			.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("session", null, null)))
			.build());

		AcpAsyncAgent first = factory.create(InMemoryTransportPair.create().agentTransport());
		AcpAsyncAgent second = factory.create(InMemoryTransportPair.create().agentTransport());

		assertThat(first).isNotSameAs(second);
	}

	@Test
	void syncFactoryAdaptsToAsyncRuntime() {
		AcpAgentFactory factory = AcpAgentFactory.sync(transport -> AcpAgent.sync(transport)
			.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse("session", null, null))
			.build());

		AcpAsyncAgent agent = factory.create(InMemoryTransportPair.create().agentTransport());

		assertThat(agent).isNotNull();
	}

}
