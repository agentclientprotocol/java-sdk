/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandlingException;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import org.jspecify.annotations.Nullable;

/**
 * Lets an {@code @Prompt} handler stream its reply as a Mutiny {@link Multi}: each
 * {@code String} item is sent as an agent message chunk, each
 * {@link AcpSchema.SessionUpdate} item as that update, each {@link AcpSchema.ContentBlock}
 * as a message chunk holding it, and the turn ends ({@code end_turn}) when the stream
 * completes. A failed stream fails the prompt. When the prompt is cancelled the stream's
 * subscription is cancelled and the turn ends {@code cancelled}. Only {@code @Prompt} may
 * return a {@code Multi}.
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
			stream((Multi<?>) returnValue, prompt);
		}
		return prompt.isCancelled() ? AcpSchema.PromptResponse.cancelled() : AcpSchema.PromptResponse.endTurn();
	}

	/** Sends every item until the stream ends or the prompt is cancelled. */
	private static void stream(Multi<?> multi, SyncPromptContext prompt) {
		CompletableFuture<@Nullable Void> done = new CompletableFuture<>();
		AtomicReference<@Nullable Cancellable> subscription = new AtomicReference<>();
		Runnable cancel = () -> {
			Cancellable current = subscription.get();
			if (current != null) {
				current.cancel();
			}
		};
		subscription.set(multi.subscribe().with(item -> {
			try {
				send(prompt, item);
			}
			catch (RuntimeException e) {
				cancel.run();
				done.completeExceptionally(e);
			}
		}, done::completeExceptionally, () -> done.complete(null)));
		prompt.onCancel(() -> {
			cancel.run();
			done.complete(null);
		});
		try {
			done.join();
		}
		catch (CompletionException e) {
			Throwable cause = e.getCause();
			throw (cause instanceof RuntimeException runtime) ? runtime
					: new ReturnValueHandlingException("The @Prompt Multi failed", e);
		}
	}

	private static void send(SyncPromptContext prompt, Object item) {
		if (item instanceof String text) {
			prompt.sendMessage(text);
		}
		else if (item instanceof AcpSchema.SessionUpdate update) {
			prompt.sendUpdate(update);
		}
		else if (item instanceof AcpSchema.ContentBlock block) {
			prompt.sendUpdate(new AcpSchema.AgentMessageChunk(block));
		}
		else {
			throw new ReturnValueHandlingException("A Multi from @Prompt emits String, SessionUpdate or "
					+ "ContentBlock items, not " + item.getClass().getName());
		}
	}

}
