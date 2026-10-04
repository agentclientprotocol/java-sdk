/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import com.agentclientprotocol.sdk.integration.AcpTransportThreads;
import com.agentclientprotocol.sdk.util.PlatformThreads;

import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.thread.Threading;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.support.ExecutorServiceAdapter;

/**
 * The threads the agent runs on, by Spring Boot's own opt-in to virtual threads
 * ({@code spring.threads.virtual.enabled=true} on JDK 21 and later).
 * <p>
 * The handler methods ({@code spring.acp.agent.handler-executor}):
 * <ul>
 * <li>A bean name: that {@code Executor} bean, adapted to an {@code ExecutorService} when it is
 * a plain {@code TaskExecutor}.</li>
 * <li>{@code none}: the SDK's pool of platform threads.</li>
 * <li>Unset: with virtual threads on, the context's {@code applicationTaskExecutor}, where it
 * starts a virtual thread per task; otherwise the SDK's pool of platform threads, on every JDK,
 * since the application has not opted into virtual threads. Without virtual threads that
 * executor is a pool of 8 threads by default, which would cap the prompts served at once; name
 * the bean to use it anyway.</li>
 * </ul>
 * The listener: the {@code applicationTaskExecutor} with virtual threads on, else Jetty's pool
 * of platform threads.
 */
final class HandlerExecutors {

	static final String NONE = "none";

	private HandlerExecutors() {
	}

	static ExecutorService resolve(ApplicationContext context, AcpAgentProperties properties) {
		return resolve(context, properties, virtualThreadsActive(context));
	}

	/**
	 * The handler executor, with Spring Boot's virtual-thread opt-in given: tests decide it on any
	 * JDK, where {@link Threading#VIRTUAL} is never active before 21.
	 */
	static ExecutorService resolve(ApplicationContext context, AcpAgentProperties properties,
			boolean virtualThreads) {
		String name = properties.getHandlerExecutor();
		if (name == null) {
			Executor applicationTaskExecutor = virtualThreads ? applicationTaskExecutor(context) : null;
			if (applicationTaskExecutor == null) {
				return PlatformPool.POOL;
			}
			return adapt(applicationTaskExecutor);
		}
		if (NONE.equalsIgnoreCase(name)) {
			return PlatformPool.POOL;
		}
		Object bean = context.getBean(name);
		if (bean instanceof Executor executor) {
			return adapt(executor);
		}
		throw new IllegalStateException(
				"spring.acp.agent.handler-executor=" + name + " names a " + bean.getClass().getName() + ", not an Executor");
	}

	/** The listener's threads: the virtual-thread task executor, else platform threads. */
	static AcpTransportThreads listenerThreads(ApplicationContext context) {
		return listenerThreads(context, virtualThreadsActive(context));
	}

	/** The listener's threads, with Spring Boot's virtual-thread opt-in given. */
	static AcpTransportThreads listenerThreads(ApplicationContext context, boolean virtualThreads) {
		Executor applicationTaskExecutor = virtualThreads ? applicationTaskExecutor(context) : null;
		return (applicationTaskExecutor != null) ? AcpTransportThreads.executor(applicationTaskExecutor)
				: AcpTransportThreads.platform();
	}

	/** Spring Boot's opt-in: {@code spring.threads.virtual.enabled=true} on JDK 21 and later. */
	private static boolean virtualThreadsActive(ApplicationContext context) {
		return Threading.VIRTUAL.isActive(context.getEnvironment());
	}

	/** The context's {@code applicationTaskExecutor}, or null when it has none. */
	private static @org.jspecify.annotations.Nullable Executor applicationTaskExecutor(ApplicationContext context) {
		String name = TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME;
		if (!context.containsBean(name) || !(context.getBean(name) instanceof Executor executor)) {
			return null;
		}
		return executor;
	}

	private static ExecutorService adapt(Executor executor) {
		if (executor instanceof ExecutorService executorService) {
			return executorService;
		}
		// Submitted tasks are FutureTasks, so cancelling a handler still interrupts it.
		return new ExecutorServiceAdapter(executor::execute);
	}

	/**
	 * The SDK's pool of daemon platform threads, shared by the agents of every context in the JVM
	 * as the SDK shares its own; idle threads end after a minute.
	 */
	private static final class PlatformPool {

		static final ExecutorService POOL = PlatformThreads.newCachedPool("acp-agent-sync-handler");

	}

}
