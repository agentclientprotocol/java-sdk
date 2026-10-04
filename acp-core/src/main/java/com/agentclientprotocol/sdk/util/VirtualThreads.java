/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ThreadFactory;

import org.jspecify.annotations.Nullable;

/**
 * Virtual threads where the JDK has them (21 and later), without needing JDK 21 to compile:
 * the SDK targets Java 17, so it reaches {@code Thread.ofVirtual()} reflectively.
 *
 * <p>Internal to the SDK; not part of its API.
 */
public final class VirtualThreads {

	/** {@code Thread.ofVirtual().name(String).factory()}, or null before JDK 21. */
	private static final @Nullable MethodHandle NAMED_FACTORY = namedFactory();

	private static final @Nullable MethodHandle IS_VIRTUAL = isVirtualHandle();

	private VirtualThreads() {
	}

	/**
	 * Returns whether this JDK has virtual threads.
	 * @return true on JDK 21 and later
	 */
	public static boolean isSupported() {
		return NAMED_FACTORY != null;
	}

	/**
	 * Returns whether {@code thread} is a virtual thread.
	 * @param thread the thread
	 * @return true for a virtual thread; always false before JDK 21
	 */
	public static boolean isVirtual(Thread thread) {
		if (IS_VIRTUAL == null) {
			return false;
		}
		try {
			return (boolean) IS_VIRTUAL.invokeExact(thread);
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
		if (NAMED_FACTORY != null) {
			try {
				return (ThreadFactory) NAMED_FACTORY.invoke(name);
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
