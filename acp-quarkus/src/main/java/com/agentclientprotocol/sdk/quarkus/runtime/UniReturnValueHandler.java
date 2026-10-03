/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.handler.StringToPromptResponseHandler;
import com.agentclientprotocol.sdk.agent.support.handler.VoidHandler;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.converters.uni.UniReactorConverters;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Lets a handler return a Mutiny {@link Uni}: its item is the handler's result, read as
 * the SDK reads the same value returned directly. A {@code Uni<String>} from
 * {@code @Prompt} sends the text to the client and ends the turn, and a {@code Uni<Void>}
 * from {@code @Prompt} ends the turn.
 * <p>
 * The annotation runtime dispatches on a sync agent, so the handler's thread waits for
 * the item, as it does for a {@code Mono}; the prompt and request timeouts bound the wait.
 * When an {@code @Prompt}'s prompt is cancelled, the {@code Uni}'s subscription is
 * cancelled and the turn ends {@code cancelled}.
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
		Mono<?> mono = uni.convert().with(UniReactorConverters.toMono());
		SyncPromptContext prompt = context.getSyncPromptContext().orElse(null);
		if (prompt != null) {
			// A cancelled prompt cancels the Uni's subscription and ends the turn cancelled.
			Sinks.Empty<Void> cancelled = Sinks.empty();
			prompt.onCancel(cancelled::tryEmitEmpty);
			mono = mono.takeUntilOther(cancelled.asMono());
		}
		Object item = mono.block();
		if (prompt != null && prompt.isCancelled()) {
			return AcpSchema.PromptResponse.cancelled();
		}
		if (item == null) {
			return none.handleReturnValue(null, returnType, context);
		}
		if (item instanceof String) {
			return text.handleReturnValue(item, returnType, context);
		}
		return item;
	}

}
