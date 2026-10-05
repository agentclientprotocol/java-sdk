/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.agentclientprotocol.sdk.util.VirtualThreads;
import org.jspecify.annotations.Nullable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The default executor of synchronous agents' handlers, shared by every synchronous agent in the
 * JVM that was not given a {@code handlerExecutor}, and created on first use: on JDK 21 and later
 * a virtual thread per handler call, named {@code acp-agent-sync-handler}; before, a cached pool
 * of daemon threads of that name. Its threads may block, and neither kind keeps the JVM alive. On
 * JDK 21 to 23 a handler that blocks inside a {@code synchronized} block pins its carrier thread
 * (JEP 491 removed that in JDK 24).
 */
final class SyncHandlerScheduler {

	static final Scheduler DEFAULT = Schedulers.fromExecutorService(executor(), "acp-agent-sync-handler");

	private static ExecutorService executor() {
		return executor(VirtualThreads.newPerTaskExecutor("acp-agent-sync-handler"));
	}

	/**
	 * The virtual-thread executor when there is one, else the cached pool; tests give either on
	 * any JDK.
	 */
	static ExecutorService executor(@Nullable ExecutorService virtual) {
		if (virtual != null) {
			return virtual;
		}
		return Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "acp-agent-sync-handler");
			t.setDaemon(true);
			return t;
		});
	}

	private SyncHandlerScheduler() {
	}

}
