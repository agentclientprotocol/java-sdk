/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.util.concurrent.Executors;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The default executor of synchronous clients' handlers and session update consumers: a cached
 * pool of daemon threads named {@code acp-sync-handler}, shared by every synchronous client in the
 * JVM that was not given a {@code handlerExecutor}, and created on first use. Its threads may
 * block, and as daemon threads they do not keep the JVM alive.
 */
final class SyncHandlerScheduler {

	static final Scheduler DEFAULT = Schedulers.fromExecutorService(Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "acp-sync-handler");
		t.setDaemon(true);
		return t;
	}), "acp-sync-handler");

	private SyncHandlerScheduler() {
	}

}
