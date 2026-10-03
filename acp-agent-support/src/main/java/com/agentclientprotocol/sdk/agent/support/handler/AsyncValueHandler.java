/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.CompletionStage;

import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Handles a value a handler returns later: a {@link Mono}, a {@link CompletionStage} (such as
 * a {@code CompletableFuture}) or another Reactive Streams {@link Publisher} of at most one
 * value. The runtime waits for the value on the handler's thread, and it means what returning
 * the value itself means: a prompt method's {@code String} is sent to the client as an agent
 * message chunk and ends the turn, as {@link StringToPromptResponseHandler} does. An empty
 * {@code Mono} or {@code Publisher}, or a {@code null} stage value, produces no response; a
 * {@code Publisher} that emits more than one value fails the call.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class AsyncValueHandler implements ReturnValueHandler {

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		Class<?> type = returnType.getParameterType();
		return Publisher.class.isAssignableFrom(type) || CompletionStage.class.isAssignableFrom(type);
	}

	@Override
	public @Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context) {
		if (returnValue == null) {
			return null;
		}
		Object value = await(returnValue);
		if (value instanceof String text && AcpSchema.METHOD_SESSION_PROMPT.equals(context.getAcpMethod())) {
			return StringToPromptResponseHandler.endTurnWith(text, context);
		}
		return value;
	}

	private static @Nullable Object await(Object async) {
		if (async instanceof CompletionStage<?> stage) {
			return Mono.fromCompletionStage(stage).block();
		}
		if (async instanceof Mono<?> mono) {
			return mono.block();
		}
		return Flux.from((Publisher<?>) async).singleOrEmpty().block();
	}

	/**
	 * The value type of an async return type, such as {@code PromptResponse} for
	 * {@code Mono<PromptResponse>}.
	 * @param returnType the return type parameter
	 * @return the type argument, or {@code Object} when it is not a class
	 */
	public static Class<?> getValueType(AcpMethodParameter returnType) {
		Type genericType = returnType.getGenericType();
		if (genericType instanceof ParameterizedType pt) {
			Type[] typeArgs = pt.getActualTypeArguments();
			if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> c) {
				return c;
			}
		}
		return Object.class;
	}

}
