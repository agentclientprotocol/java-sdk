/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Virtual threads where the JDK has them (21 and later), daemon platform threads otherwise. */
class VirtualThreadsTest {

	private static final boolean JDK_21 = Runtime.version().feature() >= 21;

	@Test
	void supportedExactlyOnJdk21AndLater() {
		assertThat(VirtualThreads.isSupported()).isEqualTo(JDK_21);
	}

	@Test
	void theFactoryMakesNamedVirtualThreadsOnJdk21ElseNamedDaemonThreads() throws Exception {
		ThreadFactory factory = VirtualThreads.factoryOrDaemon("acp-test-vt");
		CompletableFuture<Thread> ran = new CompletableFuture<>();
		Thread thread = factory.newThread(() -> ran.complete(Thread.currentThread()));
		thread.start();
		Thread current = ran.get(5, TimeUnit.SECONDS);
		assertThat(current.getName()).isEqualTo("acp-test-vt");
		assertThat(current.isDaemon()).isTrue();
		assertThat(VirtualThreads.isVirtual(current)).isEqualTo(JDK_21);
	}

}
