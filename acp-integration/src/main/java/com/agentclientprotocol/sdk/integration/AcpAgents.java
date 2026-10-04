/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery.AgentCandidate;

/**
 * Assembles the application's {@code @AcpAgent} into an {@link AcpAgentSupport.Builder}, the same
 * way in every framework. The caller then sets a transport and builds ({@link AcpAgentHost}), or
 * builds a factory for a listener ({@link AcpListeners}).
 */
public final class AcpAgents {

	private AcpAgents() {
	}

	/**
	 * A builder for the agent: its handlers discovered on the user class (superclasses included),
	 * invoked on the candidate's instance (a proxy keeps its advice), with the settings' timeouts,
	 * then the given interceptors, argument resolvers and return value handlers, each list in
	 * order.
	 * @param agent the {@code @AcpAgent} bean
	 * @param settings the agent settings; an unset timeout keeps the SDK default
	 * @param interceptors the interceptors, in order
	 * @param resolvers the argument resolvers, in order
	 * @param returnValueHandlers the return value handlers, in order
	 * @param <T> the agent type
	 * @return a builder holding everything but the transport
	 */
	public static <T> AcpAgentSupport.Builder builder(AgentCandidate<T> agent, AcpAgentSettings settings,
			List<? extends AcpInterceptor> interceptors, List<? extends ArgumentResolver> resolvers,
			List<? extends ReturnValueHandler> returnValueHandlers) {
		AcpAgentSupport.Builder builder = AcpAgentSupport.builder().agent(agent.userClass(), agent.instance()::get);
		Duration requestTimeout = settings.requestTimeout();
		if (requestTimeout != null) {
			builder.requestTimeout(requestTimeout);
		}
		Duration cancelGracePeriod = settings.cancelGracePeriod();
		if (cancelGracePeriod != null) {
			builder.cancelGracePeriod(cancelGracePeriod);
		}
		Duration maxPromptDuration = settings.maxPromptDuration();
		if (maxPromptDuration != null) {
			builder.maxPromptDuration(maxPromptDuration);
		}
		interceptors.forEach(builder::interceptor);
		resolvers.forEach(builder::argumentResolver);
		returnValueHandlers.forEach(builder::returnValueHandler);
		return builder;
	}

}
