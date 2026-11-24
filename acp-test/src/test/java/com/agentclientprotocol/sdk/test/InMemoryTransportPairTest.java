/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deprecated test-kit name delegates to the supported in-process transport, whose own tests
 * are in acp-core.
 */
@SuppressWarnings("deprecation")
class InMemoryTransportPairTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void eachCallReturnsTheSameTransports() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		assertThat(pair.clientTransport()).isSameAs(pair.clientTransport());
		assertThat(pair.agentTransport()).isSameAs(pair.agentTransport());
	}

	@Test
	void theTransportsAreConnected() throws InterruptedException {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		CountDownLatch received = new CountDownLatch(1);
		pair.agentTransport().start(msg -> msg.doOnNext(m -> received.countDown()).then(Mono.empty())).block(TIMEOUT);

		pair.clientTransport()
			.sendMessage(new AcpSchema.JSONRPCRequest(AcpSchema.METHOD_INITIALIZE, "1",
					new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())))
			.block(TIMEOUT);

		assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void closeGracefullyEndsBothSides() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();

		pair.closeGracefully().block(TIMEOUT);

		pair.agentTransport().awaitTermination().block(TIMEOUT);
		pair.clientTransport().awaitTermination().block(TIMEOUT);
	}

}
