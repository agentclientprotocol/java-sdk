/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.util.concurrent.Executor;

import com.agentclientprotocol.sdk.util.Assert;
import org.jspecify.annotations.Nullable;

/**
 * The threads a framework's ACP network transports run on: the WebSocket and Streamable HTTP
 * client transports ({@link AcpClientTransports}) and the SDK's listener ({@link AcpListeners}).
 * A framework picks one by its own convention:
 * <ul>
 * <li>{@link #executor(Executor)}: the framework's executor for blocking work, such as its
 * virtual-thread executor. The transports run on it and never shut it down. The listener takes
 * it on JDK 21 and later only, and needs one that starts a thread per task.</li>
 * <li>{@link #sdkDefault()}: the SDK's own threads, which are virtual threads on JDK 21 and
 * later, and pools of platform threads before.</li>
 * <li>{@link #platform()}: pools of platform threads on every JDK, for an application that has
 * not opted into virtual threads (Spring Boot without {@code spring.threads.virtual.enabled}).</li>
 * </ul>
 * The stdio transports are not affected: their reader and writer threads block on the process's
 * pipes for as long as it runs, which a virtual thread would not make cheaper.
 */
public final class AcpTransportThreads {

	private static final AcpTransportThreads SDK_DEFAULT = new AcpTransportThreads(null, true);

	private static final AcpTransportThreads PLATFORM = new AcpTransportThreads(null, false);

	private final @Nullable Executor executor;

	private final boolean virtualThreads;

	private AcpTransportThreads(@Nullable Executor executor, boolean virtualThreads) {
		this.executor = executor;
		this.virtualThreads = virtualThreads;
	}

	/**
	 * The SDK's own threads: virtual threads on JDK 21 and later, pools of platform threads
	 * before.
	 * @return the SDK's default
	 */
	public static AcpTransportThreads sdkDefault() {
		return SDK_DEFAULT;
	}

	/**
	 * Pools of platform threads of the SDK's own, on every JDK.
	 * @return platform threads
	 */
	public static AcpTransportThreads platform() {
		return PLATFORM;
	}

	/**
	 * The framework's executor, which must allow blocking and not cap its threads: a
	 * virtual-thread executor or a framework's worker pool.
	 * @param executor the executor; the framework owns it
	 * @return the framework's executor
	 * @throws IllegalArgumentException if {@code executor} is null
	 */
	public static AcpTransportThreads executor(Executor executor) {
		Assert.notNull(executor, "The executor can not be null");
		return new AcpTransportThreads(executor, true);
	}

	/**
	 * Returns the framework's executor, if one was chosen.
	 * @return the executor, or null for the SDK's own threads
	 */
	public @Nullable Executor executor() {
		return executor;
	}

	/**
	 * Returns whether the SDK's own threads may be virtual where the JDK has them.
	 * @return false for platform threads on every JDK
	 */
	public boolean virtualThreads() {
		return virtualThreads;
	}

	@Override
	public String toString() {
		return (executor != null) ? "executor " + executor : (virtualThreads ? "SDK default" : "platform threads");
	}

}
