/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared timeout timer serves every session in the JVM, so one session's error handler
 * must not be able to delay another session's timeout.
 */
class AcpSchedulersTest {

	@Test
	void aBlockingTimeoutHandlerDoesNotDelayAnotherSessionsTimeout() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch firstBlocked = new CountDownLatch(1);
		AcpSchedulers.withTimeout(Mono.never(), Duration.ofMillis(20)).subscribe(v -> {
		}, error -> {
			firstBlocked.countDown();
			try {
				release.await(5, TimeUnit.SECONDS); // a misbehaving subscriber
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		});
		assertThat(firstBlocked.await(2, TimeUnit.SECONDS)).isTrue();

		long start = System.nanoTime();
		CountDownLatch secondTimedOut = new CountDownLatch(1);
		AcpSchedulers.withTimeout(Mono.never(), Duration.ofMillis(50)).subscribe(v -> {
		}, error -> {
			if (error instanceof TimeoutException) {
				secondTimedOut.countDown();
			}
		});
		try {
			assertThat(secondTimedOut.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1000));
		}
		finally {
			release.countDown();
		}
	}

}
