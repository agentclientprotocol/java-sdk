/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.util.VirtualThreads;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default handler executor on either JDK, whichever JDK runs the test: the virtual-thread
 * executor where there is one (JDK 21 and later), else a cached pool of named daemon threads.
 */
class SyncHandlerSchedulerTest {

	@Test
	void theVirtualThreadExecutorIsUsedWhenThereIsOne() {
		ExecutorService virtual = Executors.newSingleThreadExecutor();
		try {
			assertThat(SyncHandlerScheduler.executor(virtual)).isSameAs(virtual);
		}
		finally {
			virtual.shutdownNow();
		}
	}

	@Test
	void withoutOneACachedPoolOfNamedDaemonThreads() throws Exception {
		ExecutorService pool = SyncHandlerScheduler.executor(null);
		try {
			Thread thread = pool.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
			assertThat(thread.getName()).isEqualTo("acp-agent-sync-handler");
			assertThat(thread.isDaemon()).isTrue();
			assertThat(VirtualThreads.isVirtual(thread)).isFalse();
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void theDefaultIsVirtualExactlyWhereTheJdkHasVirtualThreads() throws Exception {
		CompletableFuture<Thread> ran = new CompletableFuture<>();
		SyncHandlerScheduler.DEFAULT.schedule(() -> ran.complete(Thread.currentThread()));
		Thread thread = ran.get(5, TimeUnit.SECONDS);
		assertThat(thread.getName()).isEqualTo("acp-agent-sync-handler");
		assertThat(VirtualThreads.isVirtual(thread)).isEqualTo(VirtualThreads.isSupported());
	}

}
