/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.concurrent.Executors;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The default executor of synchronous agents' handlers: a cached pool of daemon threads named
 * {@code acp-agent-sync-handler}, shared by every synchronous agent in the JVM that was not given
 * a {@code handlerExecutor}, and created on first use. Its threads may block, and as daemon threads
 * they do not keep the JVM alive.
 */
final class SyncHandlerScheduler {

	static final Scheduler DEFAULT = Schedulers.fromExecutorService(Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "acp-agent-sync-handler");
		t.setDaemon(true);
		return t;
	}), "acp-agent-sync-handler");

	private SyncHandlerScheduler() {
	}

}
