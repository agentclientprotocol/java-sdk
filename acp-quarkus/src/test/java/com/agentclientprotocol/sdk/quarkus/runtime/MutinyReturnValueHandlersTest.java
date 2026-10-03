/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.lang.reflect.Method;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandlingException;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MutinyReturnValueHandlersTest {

	private final UniReturnValueHandler uni = new UniReturnValueHandler();

	private final MultiReturnValueHandler multi = new MultiReturnValueHandler();

	@Test
	void supportsOnlyItsType() throws Exception {
		assertThat(uni.supportsReturnType(returnType("uni"))).isTrue();
		assertThat(uni.supportsReturnType(returnType("multi"))).isFalse();
		assertThat(multi.supportsReturnType(returnType("multi"))).isTrue();
		assertThat(multi.supportsReturnType(returnType("plain"))).isFalse();
	}

	@Test
	void uniItemIsTheResult() throws Exception {
		AcpSchema.NewSessionResponse response = new AcpSchema.NewSessionResponse("s", null, null);
		assertThat(uni.handleReturnValue(Uni.createFrom().item(response), returnType("uni"), context("session/new")))
			.isSameAs(response);
		assertThat(uni.handleReturnValue(null, returnType("uni"), context("session/new"))).isNull();
	}

	@Test
	void uniStringAndVoidFromPromptAreReadAsTheSdkReadsThem() throws Exception {
		SyncPromptContext prompt = livePrompt();
		assertThat(uni.handleReturnValue(Uni.createFrom().item("hi"), returnType("uni"), promptContext(prompt)))
			.isEqualTo(AcpSchema.PromptResponse.endTurn());
		verify(prompt).sendMessage("hi");
		assertThat(uni.handleReturnValue(Uni.createFrom().voidItem(), returnType("uni"), context("session/prompt")))
			.isEqualTo(AcpSchema.PromptResponse.endTurn());
		assertThat(uni.handleReturnValue(Uni.createFrom().voidItem(), returnType("uni"), context("session/new")))
			.isNull();
	}

	@Test
	void uniFailureFailsTheHandler() throws Exception {
		Uni<Object> failed = Uni.createFrom().failure(new IllegalStateException("boom"));
		assertThatThrownBy(() -> uni.handleReturnValue(failed, returnType("uni"), context("session/new")))
			.hasMessageContaining("boom");
	}

	@Test
	void multiItemsAreStreamedAndTheTurnEnds() throws Exception {
		SyncPromptContext prompt = mock(SyncPromptContext.class);
		when(prompt.getSessionId()).thenReturn("s1");
		AcpSchema.SessionUpdate thought = new AcpSchema.AgentThoughtChunk(new AcpSchema.TextContent("hmm"));
		Object result = multi.handleReturnValue(
				Multi.createFrom().items("text", new AcpSchema.TextContent("block"), thought), returnType("multi"),
				promptContext(prompt));
		assertThat(result).isEqualTo(AcpSchema.PromptResponse.endTurn());
		verify(prompt).sendMessage("text");
		verify(prompt).sendUpdate(any(AcpSchema.AgentMessageChunk.class));
		verify(prompt).sendUpdate(thought);
	}

	@Test
	void multiOfAnythingElseFails() throws Exception {
		SyncPromptContext prompt = mock(SyncPromptContext.class);
		assertThatThrownBy(() -> multi.handleReturnValue(Multi.createFrom().items(42), returnType("multi"),
				promptContext(prompt)))
			.isInstanceOf(ReturnValueHandlingException.class)
			.hasMessageContaining("java.lang.Integer");
	}

	@Test
	void aCancelledPromptCancelsTheUniAndEndsTheTurnCancelled() throws Exception {
		SyncPromptContext prompt = cancelledPrompt();
		assertThat(uni.handleReturnValue(Uni.createFrom().nothing(), returnType("uni"), promptContext(prompt)))
			.isEqualTo(AcpSchema.PromptResponse.cancelled());
	}

	@Test
	void aCancelledPromptCancelsTheMultiAndEndsTheTurnCancelled() throws Exception {
		SyncPromptContext prompt = cancelledPrompt();
		assertThat(multi.handleReturnValue(Multi.createFrom().nothing(), returnType("multi"), promptContext(prompt)))
			.isEqualTo(AcpSchema.PromptResponse.cancelled());
	}

	@Test
	void aFailedMultiFailsThePrompt() throws Exception {
		Multi<Object> failed = Multi.createFrom().failure(new IllegalStateException("boom"));
		assertThatThrownBy(() -> multi.handleReturnValue(failed, returnType("multi"),
				promptContext(mock(SyncPromptContext.class))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("boom");
		Multi<Object> checked = Multi.createFrom().failure(new java.io.IOException("io"));
		assertThatThrownBy(() -> multi.handleReturnValue(checked, returnType("multi"),
				promptContext(mock(SyncPromptContext.class))))
			.isInstanceOf(ReturnValueHandlingException.class)
			.hasRootCauseMessage("io");
	}

	/** A prompt that is not cancelled. */
	private static SyncPromptContext livePrompt() {
		SyncPromptContext prompt = mock(SyncPromptContext.class);
		com.agentclientprotocol.sdk.agent.PromptContext async = mock(com.agentclientprotocol.sdk.agent.PromptContext.class);
		when(async.whenCancelled()).thenReturn(reactor.core.publisher.Mono.never());
		when(prompt.async()).thenReturn(async);
		return prompt;
	}

	/** A prompt already cancelled: onCancel runs its action at once, as the SDK's does. */
	private static SyncPromptContext cancelledPrompt() {
		SyncPromptContext prompt = mock(SyncPromptContext.class);
		com.agentclientprotocol.sdk.agent.PromptContext async = mock(com.agentclientprotocol.sdk.agent.PromptContext.class);
		when(async.whenCancelled()).thenReturn(reactor.core.publisher.Mono.empty());
		when(prompt.async()).thenReturn(async);
		when(prompt.isCancelled()).thenReturn(true);
		org.mockito.Mockito.doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(prompt).onCancel(any());
		return prompt;
	}

	@Test
	void nullMultiEndsTheTurn() throws Exception {
		assertThat(multi.handleReturnValue(null, returnType("multi"), promptContext(mock(SyncPromptContext.class))))
			.isEqualTo(AcpSchema.PromptResponse.endTurn());
	}

	@Test
	void multiOutsideAPromptFails() throws Exception {
		assertThatThrownBy(() -> multi.handleReturnValue(Multi.createFrom().empty(), returnType("multi"),
				context("session/new")))
			.isInstanceOf(ReturnValueHandlingException.class)
			.hasMessageContaining("Only an @Prompt handler may return a Multi");
	}

	private static AcpMethodParameter returnType(String name) throws NoSuchMethodException {
		Method method = Handlers.class.getDeclaredMethod(name);
		return AcpMethodParameter.forReturnType(method);
	}

	private static AcpInvocationContext context(String acpMethod) {
		return AcpInvocationContext.builder().acpMethod(acpMethod).request(new Object()).build();
	}

	private static AcpInvocationContext promptContext(SyncPromptContext prompt) {
		return AcpInvocationContext.builder()
			.acpMethod("session/prompt")
			.request(new Object())
			.syncPromptContext(prompt)
			.build();
	}

	static class Handlers {

		Uni<Object> uni() {
			return Uni.createFrom().nullItem();
		}

		Multi<Object> multi() {
			return Multi.createFrom().empty();
		}

		Object plain() {
			return new Object();
		}

	}

}
