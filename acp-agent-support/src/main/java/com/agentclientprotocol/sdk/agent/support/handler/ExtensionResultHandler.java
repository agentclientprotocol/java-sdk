/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import org.jspecify.annotations.Nullable;

/**
 * Handles the return value of an {@link ExtRequest @ExtRequest} method: it passes the value through
 * unchanged, and the JSON mapper writes it as the result of the extension request. A record, a
 * {@code Map} or any other value the mapper can write works. It is one of the built-in
 * {@link ReturnValueHandler}s that {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport
 * AcpAgentSupport} registers by default, so an application never creates or registers it: the
 * method declares the return type.
 *
 * <p>It works as {@link AsyncValueHandler} does, for extension requests, and is asked last, so it
 * receives only what the other built-in handlers leave: a {@code Mono} or {@code CompletionStage}
 * is awaited first, and a {@code String} or an ACP response passes through the handler for it, with
 * the same result. A {@code null} result is answered with an internal error ({@code -32603}), so
 * return an empty map when there is nothing to return. Building the agent rejects a {@code void}
 * {@code @ExtRequest} method, and an {@code @ExtNotification} method that returns a value.
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
