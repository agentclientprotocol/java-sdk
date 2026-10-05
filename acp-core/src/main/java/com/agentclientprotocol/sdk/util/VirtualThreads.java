/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.jspecify.annotations.Nullable;

/**
 * Virtual threads where the JDK has them (21 and later), without needing JDK 21 to compile:
 * the SDK targets Java 17, so it reaches {@code Thread.ofVirtual()} and
 * {@code Executors.newThreadPerTaskExecutor} reflectively. On JDK 21 to 23 a virtual thread
 * that blocks inside a {@code synchronized} block pins its carrier thread; JDK 24 removed that
 * (JEP 491).
 *
 * <p>Internal to the SDK; not part of its API.
 */
public final class VirtualThreads {

	/** {@code Thread.ofVirtual().name(String).factory()}, or null before JDK 21. */
	private static final @Nullable MethodHandle NAMED_FACTORY = namedFactory();

	private static final @Nullable MethodHandle IS_VIRTUAL = isVirtualHandle();

	/** {@code Executors.newThreadPerTaskExecutor(ThreadFactory)}, or null before JDK 21. */
	private static final @Nullable MethodHandle PER_TASK = perTaskHandle();

	private VirtualThreads() {
	}

	/**
	 * Returns whether this JDK has virtual threads.
	 * @return true on JDK 21 and later
	 */
	public static boolean isSupported() {
		return isSupported(NAMED_FACTORY);
	}

	/*
	 * Each decision below takes the handles it decides on, so tests make both choices on any
	 * JDK: the coverage gate requires every branch of this class covered on JDK 17 and on 21.
	 */

	static boolean isSupported(@Nullable MethodHandle namedFactory) {
		return namedFactory != null;
	}

	/**
	 * Returns whether {@code thread} is a virtual thread.
	 * @param thread the thread
	 * @return true for a virtual thread; always false before JDK 21
	 */
	public static boolean isVirtual(Thread thread) {
		return isVirtual(IS_VIRTUAL, thread);
	}

	static boolean isVirtual(@Nullable MethodHandle isVirtual, Thread thread) {
		if (isVirtual == null) {
			return false;
		}
		try {
			return (boolean) isVirtual.invokeExact(thread);
		}
		catch (Throwable e) {
			return false;
		}
	}

	/**
	 * Returns a factory of virtual threads named {@code name} on JDK 21 and later, else of
	 * daemon platform threads named {@code name}. Either kind keeps no JVM alive.
	 * @param name the name of every thread the factory makes
	 * @return the factory
	 */
	public static ThreadFactory factoryOrDaemon(String name) {
		return factoryOrDaemon(NAMED_FACTORY, name);
	}

	static ThreadFactory factoryOrDaemon(@Nullable MethodHandle namedFactory, String name) {
		if (namedFactory != null) {
			try {
				return (ThreadFactory) namedFactory.invoke(name);
			}
			catch (Throwable e) {
				// Fall through to platform threads.
			}
		}
		return runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		};
	}

	/**
	 * Returns an executor that starts a virtual thread named {@code name} for each task, as
	 * {@code Executors.newVirtualThreadPerTaskExecutor()} does, on JDK 21 and later; null
	 * before. Shutting it down interrupts its running tasks on {@code shutdownNow()}.
	 * @param name the name of every thread it starts
	 * @return the executor, or null when the JDK has no virtual threads
	 */
	public static @Nullable ExecutorService newPerTaskExecutor(String name) {
		return newPerTaskExecutor(PER_TASK, NAMED_FACTORY, name);
	}

	static @Nullable ExecutorService newPerTaskExecutor(@Nullable MethodHandle perTask,
			@Nullable MethodHandle namedFactory, String name) {
		if (perTask == null || namedFactory == null) {
			return null;
		}
		try {
			return (ExecutorService) perTask.invoke(factoryOrDaemon(namedFactory, name));
		}
		catch (Throwable e) {
			return null;
		}
	}

	private static @Nullable MethodHandle perTaskHandle() {
		try {
			return MethodHandles.publicLookup()
				.findStatic(Executors.class, "newThreadPerTaskExecutor",
						MethodType.methodType(ExecutorService.class, ThreadFactory.class));
		}
		catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static @Nullable MethodHandle namedFactory() {
		try {
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			Class<?> builder = Class.forName("java.lang.Thread$Builder");
			Class<?> ofVirtual = Class.forName("java.lang.Thread$Builder$OfVirtual");
			MethodHandle create = lookup.findStatic(Thread.class, "ofVirtual", MethodType.methodType(ofVirtual));
			MethodHandle name = lookup.findVirtual(ofVirtual, "name", MethodType.methodType(ofVirtual, String.class));
			MethodHandle factory = lookup.findVirtual(builder, "factory", MethodType.methodType(ThreadFactory.class));
			// name -> Thread.ofVirtual().name(name).factory()
			MethodHandle named = MethodHandles.collectArguments(name, 0, create);
			return MethodHandles.filterReturnValue(named,
					factory.asType(MethodType.methodType(ThreadFactory.class, ofVirtual)));
		}
		catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static @Nullable MethodHandle isVirtualHandle() {
		try {
			return MethodHandles.publicLookup()
				.findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
		}
		catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

}
