/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.support.ExecutorServiceAdapter;

/**
 * The executor the agent's handler methods run on ({@code spring.acp.agent.handler-executor}).
 * <ul>
 * <li>A bean name: that {@code Executor} bean, adapted to an {@code ExecutorService} when it is
 * a plain {@code TaskExecutor}.</li>
 * <li>{@code none}: the SDK's own pool.</li>
 * <li>Unset: the context's {@code applicationTaskExecutor} when virtual threads are on
 * ({@code spring.threads.virtual.enabled=true}), where it starts a virtual thread per task.
 * Without virtual threads that executor is a pool of 8 threads by default, which would cap the
 * prompts served at once, so the SDK's own pool stays the default; name the bean to use it
 * anyway.</li>
 * </ul>
 */
final class HandlerExecutors {

	static final String NONE = "none";

	private HandlerExecutors() {
	}

	static @Nullable ExecutorService resolve(ApplicationContext context, AcpAgentProperties properties) {
		String name = properties.getHandlerExecutor();
		if (name == null) {
			boolean virtual = context.getEnvironment().getProperty("spring.threads.virtual.enabled", Boolean.class,
					false);
			String applicationTaskExecutor = TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME;
			if (!virtual || !context.containsBean(applicationTaskExecutor)) {
				return null;
			}
			name = applicationTaskExecutor;
		}
		if (NONE.equalsIgnoreCase(name)) {
			return null;
		}
		Object bean = context.getBean(name);
		if (bean instanceof ExecutorService executorService) {
			return executorService;
		}
		if (bean instanceof Executor executor) {
			// Submitted tasks are FutureTasks, so cancelling a handler still interrupts it.
			return new ExecutorServiceAdapter(executor::execute);
		}
		throw new IllegalStateException(
				"spring.acp.agent.handler-executor=" + name + " names a " + bean.getClass().getName() + ", not an Executor");
	}

}
