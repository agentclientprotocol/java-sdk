/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.concurrent.ExecutorService;

import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import com.agentclientprotocol.sdk.util.VirtualThreads;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code quarkus.acp.handler-executor}: Quarkus' virtual-thread executor where the JDK has virtual
 * threads and the bean exists, else the {@code ManagedExecutor}; {@code managed} always the latter.
 */
class AcpExecutorsTest {

	private final ExecutorService virtual = mock(ExecutorService.class);

	private final ManagedExecutor managed = mock(ManagedExecutor.class);

	@Test
	void virtualIsQuarkusVirtualThreadExecutorOnJdk21() {
		AcpExecutors executors = executors(AcpRuntimeConfig.HandlerExecutor.VIRTUAL, true, true);
		assertThat(executors.handlerExecutor()).isSameAs(virtual);
		assertThat(executors.transportThreads().executor()).isSameAs(virtual);
	}

	@Test
	void virtualBeforeJdk21IsTheManagedExecutor() {
		assertThat(executors(AcpRuntimeConfig.HandlerExecutor.VIRTUAL, true, false).handlerExecutor())
			.isSameAs(managed);
	}

	@Test
	void theInjectedChoiceAsksTheJdk() {
		AcpRuntimeConfig config = mock(AcpRuntimeConfig.class);
		when(config.handlerExecutor()).thenReturn(AcpRuntimeConfig.HandlerExecutor.VIRTUAL);
		ExecutorService chosen = new AcpExecutors(config, instance(virtual, true), instance(managed, true))
			.handlerExecutor();
		assertThat(chosen).isSameAs(VirtualThreads.isSupported() ? virtual : managed);
	}

	@Test
	void virtualWithoutTheBeanIsTheManagedExecutor() {
		assertThat(executors(AcpRuntimeConfig.HandlerExecutor.VIRTUAL, false, true).handlerExecutor())
			.isSameAs(managed);
	}

	@Test
	void managedIsTheManagedExecutorAndCarriesToTheTransports() {
		AcpExecutors executors = executors(AcpRuntimeConfig.HandlerExecutor.MANAGED, true, true);
		assertThat(executors.handlerExecutor()).isSameAs(managed);
		assertThat(executors.transportThreads().executor()).isSameAs(managed);
	}

	private AcpExecutors executors(AcpRuntimeConfig.HandlerExecutor choice, boolean virtualBean,
			boolean jdkHasVirtualThreads) {
		AcpRuntimeConfig config = mock(AcpRuntimeConfig.class);
		when(config.handlerExecutor()).thenReturn(choice);
		return new AcpExecutors(config, instance(virtual, virtualBean), instance(managed, true), jdkHasVirtualThreads);
	}

	@SuppressWarnings("unchecked")
	private static <T> Instance<T> instance(T bean, boolean resolvable) {
		Instance<T> instance = mock(Instance.class);
		when(instance.isResolvable()).thenReturn(resolvable);
		when(instance.get()).thenReturn(bean);
		return instance;
	}

}
