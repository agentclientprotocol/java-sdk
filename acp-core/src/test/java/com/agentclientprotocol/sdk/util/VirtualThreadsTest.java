/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Virtual threads where the JDK has them (21 and later), daemon platform threads otherwise. The
 * tests that give the handles make both choices on whichever JDK runs them: the coverage gate
 * requires every branch of {@link VirtualThreads} covered on JDK 17 and on JDK 21.
 */
class VirtualThreadsTest {

	private static final boolean JDK_21 = Runtime.version().feature() >= 21;

	@Test
	void supportedExactlyOnJdk21AndLater() {
		assertThat(VirtualThreads.isSupported()).isEqualTo(JDK_21);
	}

	@Test
	void theFactoryMakesNamedVirtualThreadsOnJdk21ElseNamedDaemonThreads() throws Exception {
		ThreadFactory factory = VirtualThreads.factoryOrDaemon("acp-test-vt");
		CompletableFuture<Thread> ran = new CompletableFuture<>();
		Thread thread = factory.newThread(() -> ran.complete(Thread.currentThread()));
		thread.start();
		Thread current = ran.get(5, TimeUnit.SECONDS);
		assertThat(current.getName()).isEqualTo("acp-test-vt");
		assertThat(current.isDaemon()).isTrue();
		assertThat(VirtualThreads.isVirtual(current)).isEqualTo(JDK_21);
	}

	@Test
	void thePerTaskExecutorStartsANamedVirtualThreadPerTaskOnJdk21AndIsAbsentBefore() throws Exception {
		java.util.concurrent.ExecutorService executor = VirtualThreads.newPerTaskExecutor("acp-test-task");
		if (!JDK_21) {
			assertThat(executor).isNull();
			return;
		}
		try {
			Thread thread = executor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
			assertThat(VirtualThreads.isVirtual(thread)).isTrue();
			assertThat(thread.getName()).isEqualTo("acp-test-task");
		}
		finally {
			executor.shutdownNow();
		}
	}

	// The handles a JDK without virtual threads has: none.

	@Test
	void withoutTheHandlesNothingIsVirtualAndTheFactoryMakesDaemonThreads() throws Exception {
		assertThat(VirtualThreads.isSupported(null)).isFalse();
		assertThat(VirtualThreads.isVirtual(null, Thread.currentThread())).isFalse();
		assertThat(VirtualThreads.newPerTaskExecutor(null, null, "acp-test")).isNull();
		assertThat(VirtualThreads.newPerTaskExecutor(perTaskHandle(), null, "acp-test")).isNull();
		assertDaemonPlatformThread(VirtualThreads.factoryOrDaemon(null, "acp-test-daemon"), "acp-test-daemon");
	}

	// Handles that stand in for the JDK 21 ones, so the JDK 21 choice is made on JDK 17 too.

	@Test
	void withTheHandlesTheirAnswersAreUsed() throws Exception {
		MethodHandle factory = handle("namedFactory", ThreadFactory.class, String.class);
		assertThat(VirtualThreads.isSupported(factory)).isTrue();
		assertThat(VirtualThreads.isVirtual(handle("isDaemon", boolean.class, Thread.class), daemon())).isTrue();

		ThreadFactory named = VirtualThreads.factoryOrDaemon(factory, "acp-test-named");
		assertThat(named.newThread(() -> {
		}).getName()).isEqualTo("stand-in acp-test-named");

		ExecutorService executor = VirtualThreads.newPerTaskExecutor(perTaskHandle(), factory, "acp-test-task");
		try {
			Thread thread = executor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
			assertThat(thread.getName()).isEqualTo("stand-in acp-test-task");
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void handlesThatFailFallBack() throws Exception {
		MethodHandle failingFactory = handle("failingFactory", ThreadFactory.class, String.class);
		assertThat(VirtualThreads.isVirtual(handle("failingIsVirtual", boolean.class, Thread.class),
				Thread.currentThread()))
			.isFalse();
		assertDaemonPlatformThread(VirtualThreads.factoryOrDaemon(failingFactory, "acp-test-fallback"),
				"acp-test-fallback");
		assertThat(VirtualThreads.newPerTaskExecutor(handle("failingPerTask", ExecutorService.class, ThreadFactory.class),
				handle("namedFactory", ThreadFactory.class, String.class), "acp-test"))
			.isNull();
	}

	private static void assertDaemonPlatformThread(ThreadFactory factory, String name) throws Exception {
		CompletableFuture<Thread> ran = new CompletableFuture<>();
		Thread thread = factory.newThread(() -> ran.complete(Thread.currentThread()));
		thread.start();
		Thread current = ran.get(5, TimeUnit.SECONDS);
		assertThat(current.getName()).isEqualTo(name);
		assertThat(current.isDaemon()).isTrue();
		assertThat(VirtualThreads.isVirtual(current)).isFalse();
	}

	private static Thread daemon() {
		Thread thread = new Thread(() -> {
		});
		thread.setDaemon(true);
		return thread;
	}

	private static MethodHandle perTaskHandle() throws ReflectiveOperationException {
		return MethodHandles.publicLookup()
			.findStatic(Executors.class, "newSingleThreadExecutor",
					MethodType.methodType(ExecutorService.class, ThreadFactory.class));
	}

	private static MethodHandle handle(String name, Class<?> returnType, Class<?> parameterType)
			throws ReflectiveOperationException {
		if (name.equals("isDaemon")) {
			return MethodHandles.publicLookup().findVirtual(Thread.class, name, MethodType.methodType(returnType));
		}
		return MethodHandles.lookup()
			.findStatic(VirtualThreadsTest.class, name, MethodType.methodType(returnType, parameterType));
	}

	static ThreadFactory namedFactory(String name) {
		return runnable -> new Thread(runnable, "stand-in " + name);
	}

	static ThreadFactory failingFactory(String name) {
		throw new IllegalStateException("no factory for " + name);
	}

	static boolean failingIsVirtual(Thread thread) {
		throw new IllegalStateException("cannot tell " + thread.getName());
	}

	static ExecutorService failingPerTask(ThreadFactory factory) {
		throw new IllegalStateException("no executor for " + factory);
	}

}
