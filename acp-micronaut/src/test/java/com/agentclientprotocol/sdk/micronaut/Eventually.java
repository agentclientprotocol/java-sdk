/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut;

import java.time.Duration;

/** Retries an assertion until it holds or the time is up. */
public final class Eventually {

	private Eventually() {
	}

	/**
	 * Runs the assertion until it passes, for at most {@code within}.
	 * @param within how long to keep trying
	 * @param assertion the assertion
	 */
	public static void eventually(Duration within, Runnable assertion) {
		long deadline = System.nanoTime() + within.toNanos();
		while (true) {
			try {
				assertion.run();
				return;
			}
			catch (AssertionError ex) {
				if (System.nanoTime() > deadline) {
					throw ex;
				}
			}
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted", ex);
			}
		}
	}

}
