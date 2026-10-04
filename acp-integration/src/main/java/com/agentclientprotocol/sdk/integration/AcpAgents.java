/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery.AgentCandidate;
import org.jspecify.annotations.Nullable;

/**
 * Assembles the application's {@code @AcpAgent} into an {@link AcpAgentSupport.Builder} the same
 * way in every framework, from the candidate {@link AcpAgentDiscovery} chose, the
 * {@link AcpAgentSettings}, and the framework's extension beans. The framework then finishes the
 * builder for its transport: for one agent on one transport, it sets the transport, builds, and
 * gives the agent to an {@link AcpAgentHost}; for HTTP and WebSocket, it calls
 * {@link AcpAgentSupport.Builder#buildFactory() buildFactory()} and gives the factory to
 * {@link AcpListeners}.
 *
 * <p>What the shared code does: it finds the handler methods on the candidate's user class
 * (superclasses included) and calls them on the candidate's instance, applies the settings'
 * timeouts (an unset one keeps the SDK default), and adds the interceptors, argument resolvers
 * and return value handlers in the order given. What the framework does: collect those beans in
 * its own bean order, and choose the executor the handler methods run on.
 */
public final class AcpAgents {

	private AcpAgents() {
	}

	/**
	 * Returns a builder for the agent whose handler methods run on the SDK's default executor, as
	 * {@link #builder(AgentCandidate, AcpAgentSettings, List, List, List, ExecutorService)}
	 * builds it with no executor.
	 * @param agent the {@code @AcpAgent} bean
	 * @param settings the agent settings
	 * @param interceptors the interceptors, in order
	 * @param resolvers the argument resolvers, in order
	 * @param returnValueHandlers the return value handlers, in order
	 * @param <T> the agent type
	 * @return a builder holding everything but the transport
	 * @throws IllegalArgumentException if neither the user class nor a superclass is marked
	 * {@code @AcpAgent}, or its handler methods are malformed; see
	 * {@link AcpAgentSupport.Builder#agent(Object)} for the cases
	 */
	public static <T> AcpAgentSupport.Builder builder(AgentCandidate<T> agent, AcpAgentSettings settings,
			List<? extends AcpInterceptor> interceptors, List<? extends ArgumentResolver> resolvers,
			List<? extends ReturnValueHandler> returnValueHandlers) {
		return builder(agent, settings, interceptors, resolvers, returnValueHandlers, null);
	}

	/**
	 * Returns a builder for the agent: its handler methods found on the candidate's user class
	 * (superclasses included) and called on the instance the candidate supplies, which this
	 * method asks for once, now; the settings' request timeout, cancel grace period and maximum
	 * prompt duration, each only when set; then the interceptors, argument resolvers and return
	 * value handlers, each list in order. The settings' transport and endpoint values are not
	 * used here; {@link AcpListeners} applies those.
	 *
	 * <p>The handler executor lets handler methods run on the framework's own threads: its
	 * virtual-thread executor (Micronaut on JDK 21 and later, Spring Boot with virtual threads
	 * on) or its managed worker pool (Quarkus), so they do not need a second pool beside the
	 * framework's. Handler methods block, so the executor must allow blocking. The SDK cancels a
	 * handler by cancelling the task it submitted, which interrupts the thread, and never shuts
	 * the executor down. Avoid an executor of non-daemon threads that the container does not
	 * stop when the agent's transport ends: those threads keep a stdio agent's JVM running after
	 * its client has gone.
	 * @param agent the {@code @AcpAgent} bean
	 * @param settings the agent settings; an unset timeout keeps the SDK default
	 * @param interceptors the interceptors, in order
	 * @param resolvers the argument resolvers, in order
	 * @param returnValueHandlers the return value handlers, in order
	 * @param handlerExecutor the executor the handler methods run on, or null for the SDK's
	 * default: a virtual thread per call on JDK 21 and later, before that a pool of daemon threads
	 * @param <T> the agent type
	 * @return a builder holding everything but the transport
	 * @throws IllegalArgumentException if neither the user class nor a superclass is marked
	 * {@code @AcpAgent}, or its handler methods are malformed; see
	 * {@link AcpAgentSupport.Builder#agent(Object)} for the cases
	 */
	public static <T> AcpAgentSupport.Builder builder(AgentCandidate<T> agent, AcpAgentSettings settings,
			List<? extends AcpInterceptor> interceptors, List<? extends ArgumentResolver> resolvers,
			List<? extends ReturnValueHandler> returnValueHandlers, @Nullable ExecutorService handlerExecutor) {
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
		if (handlerExecutor != null) {
			builder.handlerExecutor(handlerExecutor);
		}
		return builder;
	}

}
