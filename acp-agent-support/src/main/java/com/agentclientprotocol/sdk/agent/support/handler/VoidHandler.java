/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import org.jspecify.annotations.Nullable;

/**
 * Handles a {@code void} or {@code Void} return type: for a
 * {@link com.agentclientprotocol.sdk.annotation.Prompt @Prompt} method it ends the turn with
 * {@link PromptResponse#endTurn()}, and for a notification method ({@code @Cancel},
 * {@code @ExtNotification}) it produces nothing, as a notification gets no answer. It is one of the
 * built-in {@link ReturnValueHandler}s that
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport AcpAgentSupport} registers by
 * default, so an application never creates or registers it: the method declares the return type.
 *
 * <p>It works as {@link AsyncValueHandler} does, for a method that returns nothing. A request
 * method other than a {@code @Prompt} one cannot be {@code void}: building the agent rejects it,
 * because a request must get a result. After {@code session/cancel} the agent sends stop reason
 * {@code cancelled} instead of {@code end_turn}, as ACP requires: the SDK answers
 * {@code cancelled} once the cancel was received.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class VoidHandler implements ReturnValueHandler {

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		Class<?> type = returnType.getParameterType();
		return void.class.equals(type) || Void.class.equals(type);
	}

	@Override
	public @Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context) {
		// Only convert to PromptResponse for prompt handlers
		if ("session/prompt".equals(context.getAcpMethod())) {
			return PromptResponse.endTurn();
		}
		// For other methods, return null
		return null;
	}

}
