/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the tasks given to it one at a time, in the order given, on the threads of another
 * executor, which may run tasks concurrently. A transport uses it in place of a thread of its
 * own when the application supplies the executor: its writes stay in order, and a reader that
 * loops until its stream ends holds one of the executor's threads, not one of the SDK's. The
 * delegate is not shut down here: whoever created it owns it.
 *
 * <p>Internal to the SDK; not part of its API.
 */
public final class SerialExecutor implements Executor {

	private static final Logger logger = LoggerFactory.getLogger(SerialExecutor.class);

	private final Executor delegate;

	private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

	/** Tasks queued and not yet run; the one that raises it from zero starts the drain. */
	private final AtomicInteger pending = new AtomicInteger();

	/**
	 * Creates a serial executor on {@code delegate}.
	 * @param delegate the executor whose threads run the tasks
	 */
	public SerialExecutor(Executor delegate) {
		Assert.notNull(delegate, "The executor can not be null");
		this.delegate = delegate;
	}

	/**
	 * Queues the task, and starts running the queue on the delegate unless it already runs.
	 * @throws RejectedExecutionException if the delegate refuses to run the queue; the task is
	 * then dropped
	 */
	@Override
	public void execute(Runnable task) {
		Assert.notNull(task, "The task can not be null");
		tasks.offer(task);
		if (pending.getAndIncrement() == 0) {
			try {
				delegate.execute(this::drain);
			}
			catch (RejectedExecutionException e) {
				tasks.clear();
				pending.set(0);
				throw e;
			}
		}
	}

	private void drain() {
		do {
			Runnable task = tasks.poll();
			if (task != null) {
				try {
					task.run();
				}
				catch (Throwable error) {
					// One failed task must not stop the ones queued behind it.
					logger.warn("Task failed on serial executor", error);
				}
			}
		}
		while (pending.decrementAndGet() != 0);
	}

}
