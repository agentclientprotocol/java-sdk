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
 * Handles a {@code String} that a {@link com.agentclientprotocol.sdk.annotation.Prompt @Prompt}
 * method returns: it sends the text to the client as one {@code agent_message_chunk} session update
 * of the prompt's session, through the turn's {@link SyncPromptContext}, then ends the turn with
 * {@link PromptResponse#endTurn()}. A prompt response carries no content, so the update is how the
 * text reaches the client. A {@code null} or empty string sends nothing and still ends the turn. It
 * is one of the built-in {@link ReturnValueHandler}s that
 * {@link com.agentclientprotocol.sdk.agent.support.AcpAgentSupport AcpAgentSupport} registers by
 * default, so an application never creates or registers it: the method declares the return type.
 *
 * <p>It works as {@link AsyncValueHandler} does, for a declared {@code String} return type; a
 * {@code Mono} or {@code CompletionStage} of {@code String} reaches the same conversion through
 * {@code AsyncValueHandler}. The turn ends with stop reason {@code end_turn} also after a
 * {@code session/cancel}; to answer {@code cancelled}, return {@code PromptResponse.cancelled()}
 * instead. If the agent has already answered the prompt, because the cancel grace period or the
 * maximum prompt duration passed, the text is dropped. Outside a prompt method the string passes
 * through unchanged: an {@code @ExtRequest} method's string is its result, a notification method's
 * is ignored, and building the agent rejects a {@code String} return type on any other request
 * method. With a context that has no prompt context, as a context built by hand can be, it throws a
 * {@link ReturnValueHandlingException} for a non-empty string.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class StringToPromptResponseHandler implements ReturnValueHandler {

	/**
	 * Sends {@code text}, unless null or empty, to the client as an agent message chunk of the
	 * prompt's session, and ends the turn.
	 * @param text the text a prompt handler produced
	 * @param context the prompt's invocation context
	 * @return {@link PromptResponse#endTurn()}
	 */
	static PromptResponse endTurnWith(@Nullable String text, AcpInvocationContext context) {
		if (text != null && !text.isEmpty()) {
			SyncPromptContext prompt = context.getSyncPromptContext()
				.orElseThrow(() -> new ReturnValueHandlingException(
						"No prompt context to send the returned text to the client"));
			prompt.sendMessage(text);
		}
		return PromptResponse.endTurn();
	}

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		return String.class.equals(returnType.getParameterType());
	}

	@Override
	public @Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context) {
		// Only convert to PromptResponse for prompt handlers
		if ("session/prompt".equals(context.getAcpMethod())) {
			return endTurnWith((String) returnValue, context);
		}
		// For other methods, return as-is (may cause error if unexpected)
		return returnValue;
	}

}
