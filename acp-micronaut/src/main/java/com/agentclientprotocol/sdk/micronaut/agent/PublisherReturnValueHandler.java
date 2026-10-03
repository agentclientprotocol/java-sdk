/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * Lets a handler method return a Reactive Streams {@link Publisher}, the reactive type
 * Micronaut code commonly declares, of the method's response type: its first element is the
 * response, and an empty publisher is no response. {@code Mono} keeps the SDK's own handler.
 * Like that handler, it waits for the element on the handler's thread, since
 * {@code AcpAgentSupport} serves handlers through the sync agent.
 */
public final class PublisherReturnValueHandler implements ReturnValueHandler {

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		Class<?> type = returnType.getParameterType();
		return Publisher.class.isAssignableFrom(type) && !Mono.class.isAssignableFrom(type);
	}

	@Override
	public @Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context) {
		if (returnValue == null) {
			return null;
		}
		return Mono.from((Publisher<?>) returnValue).block();
	}

}
