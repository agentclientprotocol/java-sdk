/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent-side SDK threads that belong to one transport or endpoint, which must be gone
 * once the application has stopped. The SDK's shared pools (handler and timeout threads)
 * are JVM-wide and idle out on their own, and client threads belong to the test's
 * clients; neither is checked.
 */
final class Threads {

	private static final Set<String> PER_CONNECTION = Set.of("acp-agent-inbound", "acp-agent-outbound",
			"acp-streamable-http-keepalive");

	private Threads() {
	}

	static List<String> acpConnectionThreads() {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter(Thread::isAlive)
			.map(Thread::getName)
			.filter(name -> PER_CONNECTION.stream().anyMatch(name::startsWith))
			.toList();
	}

	/** Waits up to five seconds for the per-connection threads to end. */
	static void assertNoConnectionThreadsRemain() {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		List<String> remaining = acpConnectionThreads();
		while (!remaining.isEmpty() && System.nanoTime() < deadline) {
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			remaining = acpConnectionThreads();
		}
		assertThat(remaining).as("ACP connection threads after the application stopped").isEmpty();
	}

}
