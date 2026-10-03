/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import org.jspecify.annotations.Nullable;

/**
 * Sends the String a prompt handler returns to the client as an {@code agent_message_chunk}
 * session update of the prompt's session, then ends the turn with
 * {@link PromptResponse#endTurn()}. A prompt response carries no content, so the update is
 * how the text reaches the client. A null or empty String sends nothing.
 *
 * <p>This handler only applies to prompt handlers (session/prompt method).
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class StringToPromptResponseHandler implements ReturnValueHandler {

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		return String.class.equals(returnType.getParameterType());
	}

	@Override
	public @Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context) {
		// Only convert to PromptResponse for prompt handlers
		if ("session/prompt".equals(context.getAcpMethod())) {
			String text = (String) returnValue;
			if (text != null && !text.isEmpty()) {
				SyncPromptContext prompt = context.getSyncPromptContext()
					.orElseThrow(() -> new ReturnValueHandlingException(
							"No prompt context to send the returned text to the client"));
				prompt.sendMessage(text);
			}
			return PromptResponse.endTurn();
		}
		// For other methods, return as-is (may cause error if unexpected)
		return returnValue;
	}

}
