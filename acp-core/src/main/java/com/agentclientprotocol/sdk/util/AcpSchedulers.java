/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.util.concurrent.Executors;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Library-owned schedulers shared by every session in the JVM.
 *
 * <p>
 * Timeouts only need a timer: one daemon thread serves every {@code AcpClientSession} and
 * {@code AcpAgentSession}. Sessions used to create a scheduled pool each, which over a
 * listener transport (one agent session per remote connection) meant one idle thread per
 * connection. The shared scheduler is never disposed; its thread is a daemon and does not
 * keep the JVM alive.
 * </p>
 *
 * @author Mark Pollack
 */
public final class AcpSchedulers {

	private static final class TimeoutHolder {

		static final Scheduler SCHEDULER = Schedulers.fromExecutorService(Executors.newScheduledThreadPool(1, r -> {
			Thread t = new Thread(r, "acp-timeout");
			t.setDaemon(true);
			return t;
		}), "acp-timeout");

	}

	private AcpSchedulers() {
	}

	/**
	 * The shared scheduler for request timeouts.
	 * @return a daemon-threaded scheduler that must not be disposed by callers
	 */
	public static Scheduler timeouts() {
		return TimeoutHolder.SCHEDULER;
	}

}
