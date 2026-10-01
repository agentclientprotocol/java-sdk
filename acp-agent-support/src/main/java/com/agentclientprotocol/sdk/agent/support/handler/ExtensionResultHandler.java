/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import org.jspecify.annotations.Nullable;

/**
 * Passes through the result of an {@link ExtRequest} handler: an extension method's result
 * is any value the JSON mapper can write. Registered after the {@code Mono} and
 * {@code void} handlers, so it sees only plain values.
 *
 * @author Mark Pollack
 */
public class ExtensionResultHandler implements ReturnValueHandler {

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		return returnType.getMethod().isAnnotationPresent(ExtRequest.class);
	}

	@Override
	public @Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context) {
		return returnValue;
	}

}
