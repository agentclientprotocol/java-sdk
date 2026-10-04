/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;

/**
 * Supplies the {@link InitializeRequest} that the client sent in {@code initialize} to an
 * {@link com.agentclientprotocol.sdk.annotation.Initialize @Initialize} method that declares a
 * parameter of that type: the protocol version, the client's capabilities and its name and version.
 * It is one of the built-in {@link ArgumentResolver}s that
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport AcpAgentSupport} registers by
 * default, so an application never creates or registers it: it declares the parameter, or leaves it
 * out when the method does not need the request.
 *
 * <p>It works as {@link PromptRequestResolver} does, for {@code InitializeRequest}: building the
 * agent rejects the parameter on a method of another ACP method, a custom resolver for the type
 * replaces this one, and {@link #resolveArgument} throws an {@link ArgumentResolutionException} for
 * a context that holds another request. Only an agent with an {@code @Initialize} method uses it:
 * without one, the agent answers {@code initialize} from its annotations and calls no method.
 *
 * @author Mark Pollack
 */
public class InitializeRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return InitializeRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof InitializeRequest) {
			return request;
		}
		throw new ArgumentResolutionException(
				"Expected InitializeRequest but got: " + request.getClass().getName());
	}

}
