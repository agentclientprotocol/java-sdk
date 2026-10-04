/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.concurrent.ExecutorService;

import com.agentclientprotocol.sdk.integration.AcpTransportThreads;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import com.agentclientprotocol.sdk.util.VirtualThreads;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * The executor {@code quarkus.acp.handler-executor} selects: Quarkus' {@code ManagedExecutor} by
 * default, or with {@code virtual} its virtual-thread executor ({@code @VirtualThreads}) on JDK 21
 * and later. The
 * agent's handler methods and the client's network transports run on it; Quarkus owns it and
 * shuts it down. Part of the extension's wiring.
 */
@Singleton
public class AcpExecutors {

	private final ExecutorService executor;

	@Inject
	AcpExecutors(AcpRuntimeConfig config,
			@io.quarkus.virtual.threads.VirtualThreads Instance<ExecutorService> virtualThreads,
			Instance<ManagedExecutor> managed) {
		this(config, virtualThreads, managed, VirtualThreads.isSupported());
	}

	/** The choice with whether the JDK has virtual threads given, so tests make it on any JDK. */
	AcpExecutors(AcpRuntimeConfig config, Instance<ExecutorService> virtualThreads, Instance<ManagedExecutor> managed,
			boolean jdkHasVirtualThreads) {
		this.executor = (config.handlerExecutor() == AcpRuntimeConfig.HandlerExecutor.VIRTUAL && jdkHasVirtualThreads
				&& virtualThreads.isResolvable()) ? virtualThreads.get() : managed.get();
	}

	/** For tests: everything on {@code executor}. */
	AcpExecutors(ExecutorService executor) {
		this.executor = executor;
	}

	/**
	 * Returns the executor the handler methods run on.
	 * @return the executor
	 */
	public ExecutorService handlerExecutor() {
		return executor;
	}

	/**
	 * Returns the threads the client's WebSocket and Streamable HTTP transports run on: the same
	 * executor.
	 * @return the transport threads
	 */
	public AcpTransportThreads transportThreads() {
		return AcpTransportThreads.executor(executor);
	}

}
