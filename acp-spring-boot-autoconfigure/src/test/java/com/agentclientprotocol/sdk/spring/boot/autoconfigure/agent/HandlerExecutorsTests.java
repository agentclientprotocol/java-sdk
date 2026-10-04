/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.integration.AcpTransportThreads;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision {@link HandlerExecutors} makes once Spring Boot's virtual-thread opt-in is known.
 * The opt-in is passed in, so the decision is tested on every JDK: {@code Threading.VIRTUAL} is
 * never active before JDK 21.
 */
class HandlerExecutorsTests {

	private static final String APPLICATION_TASK_EXECUTOR = TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME;

	private final AcpAgentProperties properties = new AcpAgentProperties();

	@Test
	void withVirtualThreadsTheHandlersRunOnTheApplicationTaskExecutor() throws Exception {
		try (GenericApplicationContext context = context(new SimpleAsyncTaskExecutor("app-task-"))) {
			ExecutorService handlers = HandlerExecutors.resolve(context, properties, true);

			// A plain TaskExecutor, adapted to an ExecutorService.
			assertThat(handlers.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS))
				.startsWith("app-task-");
		}
	}

	@Test
	void anApplicationTaskExecutorThatIsAnExecutorServiceIsUsedAsIs() {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (GenericApplicationContext context = context(pool)) {
			assertThat(HandlerExecutors.resolve(context, properties, true)).isSameAs(pool);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void withoutVirtualThreadsOrWithoutTheExecutorTheSdkPool() throws Exception {
		try (GenericApplicationContext withExecutor = context(new SimpleAsyncTaskExecutor("app-task-"));
				GenericApplicationContext without = context(null);
				GenericApplicationContext notAnExecutor = context("not an executor")) {
			for (ExecutorService handlers : new ExecutorService[] {
					HandlerExecutors.resolve(withExecutor, properties, false),
					HandlerExecutors.resolve(without, properties, true),
					HandlerExecutors.resolve(notAnExecutor, properties, true) }) {
				assertThat(handlers.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS))
					.isEqualTo("acp-agent-sync-handler");
			}
		}
	}

	@Test
	void theListenerFollowsTheSameRule() {
		SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("app-task-");
		try (GenericApplicationContext context = context(executor);
				GenericApplicationContext without = context(null)) {
			assertThat(HandlerExecutors.listenerThreads(context, true).executor()).isSameAs(executor);
			AcpTransportThreads platform = HandlerExecutors.listenerThreads(context, false);
			assertThat(platform.executor()).isNull();
			assertThat(platform.virtualThreads()).isFalse();
			assertThat(HandlerExecutors.listenerThreads(without, true).executor()).isNull();
		}
	}

	private static GenericApplicationContext context(Object applicationTaskExecutor) {
		GenericApplicationContext context = new GenericApplicationContext();
		if (applicationTaskExecutor != null) {
			context.registerBean(APPLICATION_TASK_EXECUTOR, Object.class, () -> applicationTaskExecutor);
		}
		context.refresh();
		return context;
	}

}
