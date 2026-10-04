/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A serial executor runs the tasks given to it one at a time, in order, on the threads of an
 * executor that may run tasks concurrently.
 */
class SerialExecutorTest {

	@Test
	void runsTasksOneAtATimeInTheOrderGiven() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			SerialExecutor serial = new SerialExecutor(pool);
			AtomicInteger running = new AtomicInteger();
			AtomicInteger maxRunning = new AtomicInteger();
			List<Integer> order = new CopyOnWriteArrayList<>();
			CountDownLatch done = new CountDownLatch(1000);
			IntStream.range(0, 1000).forEach(i -> serial.execute(() -> {
				maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
				order.add(i);
				running.decrementAndGet();
				done.countDown();
			}));
			assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(maxRunning.get()).isEqualTo(1);
			assertThat(order).containsExactlyElementsOf(IntStream.range(0, 1000).boxed().toList());
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aFailingTaskDoesNotStopTheTasksAfterIt() throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			SerialExecutor serial = new SerialExecutor(pool);
			CountDownLatch after = new CountDownLatch(1);
			serial.execute(() -> {
				throw new IllegalStateException("boom");
			});
			serial.execute(after::countDown);
			assertThat(after.await(5, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}
	}

}
