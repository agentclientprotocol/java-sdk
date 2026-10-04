/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.agentclientprotocol.sdk.util.VirtualThreads;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The default executor of synchronous clients' handlers, shared by every synchronous client in the
 * JVM that was not given a {@code handlerExecutor}, and created on first use: on JDK 21 and later
 * a virtual thread per handler call, named {@code acp-sync-handler}; before, a cached pool of
 * daemon threads of that name. Its threads may block, and neither kind keeps the JVM alive. On JDK
 * 21 to 23 a handler that blocks inside a {@code synchronized} block pins its carrier thread (JEP
 * 491 removed that in JDK 24).
 */
final class SyncHandlerScheduler {

	static final Scheduler DEFAULT = Schedulers.fromExecutorService(executor(), "acp-sync-handler");

	private static ExecutorService executor() {
		ExecutorService virtual = VirtualThreads.newPerTaskExecutor("acp-sync-handler");
		if (virtual != null) {
			return virtual;
		}
		return Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "acp-sync-handler");
			t.setDaemon(true);
			return t;
		});
	}

	private SyncHandlerScheduler() {
	}

}
