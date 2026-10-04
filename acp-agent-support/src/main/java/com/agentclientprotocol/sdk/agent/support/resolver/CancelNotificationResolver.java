/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.CancelNotification;

/**
 * Supplies the {@link CancelNotification} that the client sent in {@code session/cancel} to a
 * {@link com.agentclientprotocol.sdk.annotation.Cancel @Cancel} method that declares a parameter of
 * that type: the id of the session whose prompt turn to stop. It is one of the built-in
 * {@link ArgumentResolver}s that {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport
 * AcpAgentSupport} registers by default, so an application never creates or registers it: it
 * declares the parameter, or leaves it out when the method does not need the notification.
 *
 * <p>It works as {@link PromptRequestResolver} does, for {@code CancelNotification}: building the
 * agent rejects the parameter on a method of another ACP method, a custom resolver for the type
 * replaces this one, and {@link #resolveArgument} throws an {@link ArgumentResolutionException} for
 * a context that holds another request. The {@code @Cancel} method also runs when the client closes
 * a session with {@code session/close}: the agent then passes it a {@code CancelNotification} that
 * carries only the session id.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class CancelNotificationResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return CancelNotification.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof CancelNotification) {
			return request;
		}
		throw new ArgumentResolutionException("Expected CancelNotification but got: "
				+ request.getClass().getName());
	}

}
