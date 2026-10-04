/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest;

/**
 * Supplies the {@link SetSessionConfigOptionRequest} that the client sent in
 * {@code session/set_config_option} to a
 * {@link com.agentclientprotocol.sdk.annotation.SetSessionConfigOption @SetSessionConfigOption}
 * method that declares a parameter of that type: the session id, the option id and the new value.
 * It is one of the built-in {@link ArgumentResolver}s that
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport AcpAgentSupport} registers by
 * default, so an application never creates or registers it: it declares the parameter, or leaves it
 * out when the method does not need the request.
 *
 * <p>It works as {@link PromptRequestResolver} does, for {@code SetSessionConfigOptionRequest}:
 * building the agent rejects the parameter on a method of another ACP method, a custom resolver for
 * the type replaces this one, and {@link #resolveArgument} throws an
 * {@link ArgumentResolutionException} for a context that holds another request. To receive only the
 * option id or a typed value, take a {@code @ConfigId} or {@code @ConfigValue} parameter instead,
 * which {@link ConfigOptionResolver} supplies.
 *
 * @author Mark Pollack
 * @since 0.12.0
 */
public class SetSessionConfigOptionRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return SetSessionConfigOptionRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof SetSessionConfigOptionRequest) {
			return request;
		}
		throw new ArgumentResolutionException(
				"Expected SetSessionConfigOptionRequest but got: "
						+ request.getClass().getName());
	}

}
