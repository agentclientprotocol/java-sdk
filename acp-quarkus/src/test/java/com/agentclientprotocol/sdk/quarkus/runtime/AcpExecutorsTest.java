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
		ExecutorService chosen = executors(AcpRuntimeConfig.HandlerExecutor.VIRTUAL, true).handlerExecutor();
		assertThat(chosen).isSameAs(VirtualThreads.isSupported() ? virtual : managed);
	}

	@Test
	void virtualWithoutTheBeanIsTheManagedExecutor() {
		assertThat(executors(AcpRuntimeConfig.HandlerExecutor.VIRTUAL, false).handlerExecutor()).isSameAs(managed);
	}

	@Test
	void managedIsTheManagedExecutorAndCarriesToTheTransports() {
		AcpExecutors executors = executors(AcpRuntimeConfig.HandlerExecutor.MANAGED, true);
		assertThat(executors.handlerExecutor()).isSameAs(managed);
		assertThat(executors.transportThreads().executor()).isSameAs(managed);
	}

	@SuppressWarnings("unchecked")
	private AcpExecutors executors(AcpRuntimeConfig.HandlerExecutor choice, boolean virtualBean) {
		AcpRuntimeConfig config = mock(AcpRuntimeConfig.class);
		when(config.handlerExecutor()).thenReturn(choice);
		Instance<ExecutorService> virtualInstance = mock(Instance.class);
		when(virtualInstance.isResolvable()).thenReturn(virtualBean);
		when(virtualInstance.get()).thenReturn(virtual);
		Instance<ManagedExecutor> managedInstance = mock(Instance.class);
		when(managedInstance.get()).thenReturn(managed);
		return new AcpExecutors(config, virtualInstance, managedInstance);
	}

}
