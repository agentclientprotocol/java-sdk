/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The SDK's pools of platform threads, for where virtual threads are absent (JDK 17) or not
 * wanted (an application that has not opted into them).
 *
 * <p>Internal to the SDK; not part of its API.
 */
public final class PlatformThreads {

	private PlatformThreads() {
	}

	/**
	 * Returns a new cached pool of daemon threads named {@code name}: a thread per task running
	 * at once, each ending after a minute idle, none keeping the JVM alive.
	 * @param name the name of every thread of the pool
	 * @return the pool
	 */
	public static ExecutorService newCachedPool(String name) {
		return Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		});
	}

}
