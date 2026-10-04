/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;

/**
 * Supplies the agent serving the connection that the request or notification arrived on: an
 * {@link AcpSyncAgent} parameter receives it, and an {@link AcpAsyncAgent} parameter the async
 * agent it wraps ({@code AcpSyncAgent.async()}). Any handler method can take one, extension methods
 * included, to send session updates and requests to that client outside a prompt turn, such as a
 * {@code ConfigOptionUpdate} after a config change. It is one of the built-in
 * {@link ArgumentResolver}s that {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport
 * AcpAgentSupport} registers by default, so an application never creates or registers it: it
 * declares the parameter.
 *
 * <p>It works as {@link PromptContextResolver} does, for the agent, with no restriction on the
 * method: every call has one. When one handler bean serves many connections (an agent factory from
 * {@code AcpAgentSupport.Builder.buildFactory()}), each call receives the agent of its own
 * connection, so keep it for that call, not in a field. The parameter's type must be exactly
 * {@code AcpSyncAgent} or {@code AcpAsyncAgent}. {@link #resolveArgument} throws an
 * {@link ArgumentResolutionException} only for a context without an agent, as a context built by
 * hand can be.
 *
 * @author Mark Pollack
 * @since 0.80.0
 */
public class AgentResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		Class<?> type = parameter.getParameterType();
		return type == AcpSyncAgent.class || type == AcpAsyncAgent.class;
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		AcpSyncAgent agent = context.getAgent()
			.orElseThrow(() -> new ArgumentResolutionException("No agent in the current context"));
		return parameter.getParameterType() == AcpAsyncAgent.class ? agent.async() : agent;
	}

}
