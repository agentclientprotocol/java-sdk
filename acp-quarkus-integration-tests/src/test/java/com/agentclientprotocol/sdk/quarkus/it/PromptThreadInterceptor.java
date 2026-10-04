/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import jakarta.inject.Singleton;

/** Records the threads the agent's prompt handler runs on. */
@Singleton
public class PromptThreadInterceptor implements AcpInterceptor {

	final List<Thread> promptThreads = new CopyOnWriteArrayList<>();

	@Override
	public boolean preInvoke(AcpInvocationContext context) {
		if ("session/prompt".equals(context.getAcpMethod())) {
			promptThreads.add(Thread.currentThread());
		}
		return true;
	}

}
