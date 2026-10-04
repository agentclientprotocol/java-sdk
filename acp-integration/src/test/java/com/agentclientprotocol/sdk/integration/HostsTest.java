/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HostsTest {

	@Test
	void aFailedGracefulCloseClosesAtOnce() {
		AtomicInteger now = new AtomicInteger();
		Hosts.await(CompletableFuture.failedFuture(new IllegalStateException("broken")), Duration.ofSeconds(1), "test",
				now::incrementAndGet);
		assertThat(now).hasValue(1);
	}

	@Test
	void aCompletedGracefulCloseIsEnough() {
		AtomicInteger now = new AtomicInteger();
		Hosts.await(CompletableFuture.completedFuture(null), Duration.ofSeconds(1), "test", now::incrementAndGet);
		assertThat(now).hasValue(0);
	}

	@Test
	void theStdioAgentTransport() {
		assertThat(AcpAgentTransports.stdio()).isInstanceOf(StdioAcpAgentTransport.class);
	}

}
