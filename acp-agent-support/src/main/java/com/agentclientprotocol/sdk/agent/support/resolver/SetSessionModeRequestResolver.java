/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeRequest;

/**
 * Supplies the {@link SetSessionModeRequest} that the client sent in {@code session/set_mode} to a
 * {@link com.agentclientprotocol.sdk.annotation.SetSessionMode @SetSessionMode} method that
 * declares a parameter of that type: the session id and the id of the mode to switch to. It is one
 * of the built-in {@link ArgumentResolver}s that
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport AcpAgentSupport} registers by
 * default, so an application never creates or registers it: it declares the parameter, or leaves it
 * out when the method does not need the request.
 *
 * <p>It works as {@link PromptRequestResolver} does, for {@code SetSessionModeRequest}: building
 * the agent rejects the parameter on a method of another ACP method, a custom resolver for the type
 * replaces this one, and {@link #resolveArgument} throws an {@link ArgumentResolutionException} for
 * a context that holds another request.
 *
 * @author Mark Pollack
 */
public class SetSessionModeRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return SetSessionModeRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof SetSessionModeRequest) {
			return request;
		}
		throw new ArgumentResolutionException("Expected SetSessionModeRequest but got: "
				+ request.getClass().getName());
	}

}
