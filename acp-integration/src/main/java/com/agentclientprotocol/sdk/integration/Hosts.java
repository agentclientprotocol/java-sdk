/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What the hosts share: waiting for a graceful close, bounded, then closing at once. */
final class Hosts {

	private static final Logger logger = LoggerFactory.getLogger(Hosts.class);

	private Hosts() {
	}

	/**
	 * Waits for {@code graceful} at most {@code timeout}; when it does not complete in time, or
	 * fails, runs {@code now}.
	 */
	static void await(CompletionStage<@Nullable Void> graceful, Duration timeout, String what, Runnable now) {
		try {
			graceful.toCompletableFuture().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
			return;
		}
		catch (TimeoutException ex) {
			logger.warn("{} did not close within {}; closing it now", what, timeout);
		}
		catch (ExecutionException ex) {
			logger.warn("{} failed to close gracefully; closing it now: {}", what, String.valueOf(ex.getCause()));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			logger.warn("Interrupted while closing {}; closing it now", what);
		}
		now.run();
	}

}
