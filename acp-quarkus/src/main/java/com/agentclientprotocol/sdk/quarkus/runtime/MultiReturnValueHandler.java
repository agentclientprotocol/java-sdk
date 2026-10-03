/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandlingException;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.smallrye.mutiny.Multi;
import org.jspecify.annotations.Nullable;

/**
 * Lets an {@code @Prompt} handler stream its reply as a Mutiny {@link Multi}: each
 * {@code String} item is sent as an agent message chunk, each
 * {@link AcpSchema.SessionUpdate} item as that update, each {@link AcpSchema.ContentBlock}
 * as a message chunk holding it, and the turn ends ({@code end_turn}) when the stream
 * completes. A failed stream fails the prompt. Only {@code @Prompt} may return a
 * {@code Multi}.
 *
 * @author Mark Pollack
 */
final class MultiReturnValueHandler implements ReturnValueHandler {

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		return Multi.class.isAssignableFrom(returnType.getParameterType());
	}

	@Override
	public @Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context) {
		SyncPromptContext prompt = context.getSyncPromptContext()
			.orElseThrow(() -> new ReturnValueHandlingException("Only an @Prompt handler may return a Multi; "
					+ returnType.getMethod().getName() + " handles " + context.getAcpMethod()));
		if (returnValue != null) {
			Multi<?> multi = (Multi<?>) returnValue;
			for (Object item : multi.subscribe().asIterable()) {
				send(prompt, item);
			}
		}
		return AcpSchema.PromptResponse.endTurn();
	}

	private static void send(SyncPromptContext prompt, Object item) {
		if (item instanceof String text) {
			prompt.sendMessage(text);
		}
		else if (item instanceof AcpSchema.SessionUpdate update) {
			prompt.sendUpdate(prompt.getSessionId(), update);
		}
		else if (item instanceof AcpSchema.ContentBlock block) {
			prompt.sendUpdate(prompt.getSessionId(), new AcpSchema.AgentMessageChunk(block));
		}
		else {
			throw new ReturnValueHandlingException("A Multi from @Prompt emits String, SessionUpdate or "
					+ "ContentBlock items, not " + item.getClass().getName());
		}
	}

}
