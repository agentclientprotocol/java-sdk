/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.handler.StringToPromptResponseHandler;
import com.agentclientprotocol.sdk.agent.support.handler.VoidHandler;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.converters.uni.UniReactorConverters;
import org.jspecify.annotations.Nullable;

/**
 * Lets a handler return a Mutiny {@link Uni}: its item is the handler's result, read as
 * the SDK reads the same value returned directly. A {@code Uni<String>} from
 * {@code @Prompt} sends the text to the client and ends the turn, and a {@code Uni<Void>}
 * from {@code @Prompt} ends the turn.
 * <p>
 * The annotation runtime dispatches on a sync agent, so the handler's thread waits for
 * the item, as it does for a {@code Mono}; the prompt and request timeouts bound the wait.
 * </p>
 *
 * @author Mark Pollack
 */
final class UniReturnValueHandler implements ReturnValueHandler {

	private final StringToPromptResponseHandler text = new StringToPromptResponseHandler();

	private final VoidHandler none = new VoidHandler();

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		return Uni.class.isAssignableFrom(returnType.getParameterType());
	}

	@Override
	public @Nullable Object handleReturnValue(@Nullable Object returnValue, AcpMethodParameter returnType,
			AcpInvocationContext context) {
		if (returnValue == null) {
			return null;
		}
		Uni<?> uni = (Uni<?>) returnValue;
		Object item = uni.convert().with(UniReactorConverters.toMono()).block();
		if (item == null) {
			return none.handleReturnValue(null, returnType, context);
		}
		if (item instanceof String) {
			return text.handleReturnValue(item, returnType, context);
		}
		return item;
	}

}
