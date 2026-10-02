/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;

/**
 * Resolves {@link AcpSyncAgent} and {@link AcpAsyncAgent} parameters to the agent serving the
 * connection a request arrived on, so any handler can send session updates and requests to
 * that client, outside a prompt turn too.
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
