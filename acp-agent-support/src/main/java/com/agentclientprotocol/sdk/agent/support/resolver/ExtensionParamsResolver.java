/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.annotation.ExtNotification;
import com.agentclientprotocol.sdk.annotation.ExtRequest;

/**
 * Resolves the parameter of an {@link ExtRequest} or {@link ExtNotification} handler to the
 * extension method's params, already read as that parameter's type.
 *
 * @author Mark Pollack
 */
public class ExtensionParamsResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return parameter.getMethod().isAnnotationPresent(ExtRequest.class)
				|| parameter.getMethod().isAnnotationPresent(ExtNotification.class);
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		return context.getRequest();
	}

}
