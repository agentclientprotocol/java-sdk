/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.support.handler.MonoHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandlerComposite;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandlingException;
import com.agentclientprotocol.sdk.agent.support.handler.VoidHandler;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolutionException;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolverComposite;
import com.agentclientprotocol.sdk.agent.support.resolver.SessionIdResolver;
import com.agentclientprotocol.sdk.annotation.SessionId;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the extension points of the annotation layer: method parameters, the
 * resolver and return-value composites, and the invocation context.
 */
class InvocationInfrastructureTest {

	@SuppressWarnings("unused")
	static class Handlers {

		Mono<PromptResponse> prompt(@SessionId String sessionId, PromptRequest request) {
			return Mono.just(PromptResponse.endTurn());
		}

		@SuppressWarnings("rawtypes")
		Mono raw() {
			return Mono.empty();
		}

		void nothing() {
		}

	}

	private static Method method(String name) {
		for (Method method : Handlers.class.getDeclaredMethods()) {
			if (method.getName().equals(name)) {
				return method;
			}
		}
		throw new AssertionError(name);
	}

	private static AcpInvocationContext context(String sessionId) {
		return AcpInvocationContext.builder()
			.acpMethod("session/prompt")
			.request(new PromptRequest(sessionId, List.of()))
			.sessionId(sessionId)
			.build();
	}

	@Test
	void methodParameterDescribesAParameterAndTheReturnType() {
		Method prompt = method("prompt");
		AcpMethodParameter sessionId = new AcpMethodParameter(prompt, 0);
		AcpMethodParameter returnType = AcpMethodParameter.forReturnType(prompt);

		assertThat(sessionId.getMethod()).isEqualTo(prompt);
		assertThat(sessionId.getIndex()).isZero();
		assertThat(sessionId.getParameterType()).isEqualTo(String.class);
		assertThat(sessionId.hasAnnotation(SessionId.class)).isTrue();
		assertThat(sessionId.getAnnotation(Deprecated.class)).isNull();
		assertThat(sessionId.isReturnType()).isFalse();
		assertThat(sessionId.toString()).startsWith("parameter 0 (").endsWith(") of prompt");

		assertThat(returnType.isReturnType()).isTrue();
		assertThat(returnType.getName()).isNull();
		assertThat(returnType.getAnnotations()).isEmpty();
		assertThat(returnType.getParameterType()).isEqualTo(Mono.class);
		assertThat(returnType).hasToString("return type of prompt");
	}

	@Test
	void methodParametersAreEqualByMethodAndIndex() {
		Method prompt = method("prompt");

		assertThat(new AcpMethodParameter(prompt, 1)).isEqualTo(new AcpMethodParameter(prompt, 1))
			.hasSameHashCodeAs(new AcpMethodParameter(prompt, 1))
			.isNotEqualTo(new AcpMethodParameter(prompt, 0))
			.isNotEqualTo(AcpMethodParameter.forReturnType(method("raw")))
			.isNotEqualTo("parameter");
	}

	@Test
	void monoGenericTypeIsTheTypeArgumentOrObject() {
		assertThat(MonoHandler.getMonoGenericType(AcpMethodParameter.forReturnType(method("prompt"))))
			.isEqualTo(PromptResponse.class);
		assertThat(MonoHandler.getMonoGenericType(AcpMethodParameter.forReturnType(method("raw"))))
			.isEqualTo(Object.class);
	}

	@Test
	void monoHandlerPassesNullThrough() {
		AcpMethodParameter returnType = AcpMethodParameter.forReturnType(method("prompt"));

		assertThat(new MonoHandler().handleReturnValue(null, returnType, context("s"))).isNull();
	}

	@Test
	void resolverCompositeUsesTheFirstSupportingResolverAndCachesTheChoice() {
		AtomicInteger asked = new AtomicInteger();
		ArgumentResolver counting = new ArgumentResolver() {

			@Override
			public boolean supportsParameter(AcpMethodParameter parameter) {
				asked.incrementAndGet();
				return false;
			}

			@Override
			public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
				throw new AssertionError("not supported");
			}

		};
		ArgumentResolverComposite composite = new ArgumentResolverComposite().addResolver(counting)
			.addResolvers(List.of(new SessionIdResolver()));
		AcpMethodParameter sessionId = new AcpMethodParameter(method("prompt"), 0);

		assertThat(composite.supportsParameter(sessionId)).isTrue();
		assertThat(composite.resolveArgument(sessionId, context("s1"))).isEqualTo("s1");
		assertThat(asked).as("the choice is cached").hasValue(1);
		composite.clearCache();
		composite.supportsParameter(sessionId);
		assertThat(asked).hasValue(2);
		assertThat(composite.getResolvers()).hasSize(2);
	}

	@Test
	void resolverCompositeRefusesAParameterNoResolverSupports() {
		ArgumentResolverComposite composite = new ArgumentResolverComposite();
		AcpMethodParameter request = new AcpMethodParameter(method("prompt"), 1);

		assertThat(composite.supportsParameter(request)).isFalse();
		assertThatThrownBy(() -> composite.resolveArgument(request, context("s1")))
			.isInstanceOf(ArgumentResolutionException.class)
			.hasMessageContaining(PromptRequest.class.getName());
	}

	@Test
	void sessionIdResolverRequiresASessionId() {
		AcpInvocationContext noSession = AcpInvocationContext.builder()
			.acpMethod("initialize")
			.request(new Object())
			.build();

		assertThatThrownBy(
				() -> new SessionIdResolver().resolveArgument(new AcpMethodParameter(method("prompt"), 0), noSession))
			.isInstanceOf(ArgumentResolutionException.class)
			.hasMessageContaining("Session ID");
	}

	@Test
	void returnValueCompositeRefusesAReturnTypeNoHandlerSupports() {
		ReturnValueHandlerComposite composite = new ReturnValueHandlerComposite()
			.addHandlers(List.of(new VoidHandler()));
		AcpMethodParameter monoReturn = AcpMethodParameter.forReturnType(method("prompt"));

		assertThat(composite.getHandlers()).hasSize(1);
		assertThat(composite.supportsReturnType(monoReturn)).isFalse();
		assertThat(composite.supportsReturnType(AcpMethodParameter.forReturnType(method("nothing")))).isTrue();
		assertThatThrownBy(() -> composite.handleReturnValue(Mono.empty(), monoReturn, context("s")))
			.isInstanceOf(ReturnValueHandlingException.class)
			.hasMessageContaining(Mono.class.getName());
	}

	@Test
	void invocationContextCarriesTypedAttributes() {
		AcpInvocationContext context = context("s1");
		context.setAttribute("count", 3);

		assertThat(context.getAttribute("count")).contains(3);
		assertThat(context.getAttribute("count", Integer.class)).contains(3);
		assertThat(context.getAttribute("count", String.class)).isEmpty();
		assertThat(context.getAttribute("missing")).isEmpty();
		assertThat(context.getRequest(PromptRequest.class).sessionId()).isEqualTo("s1");
		assertThat(context.getPromptContext()).isEmpty();
		assertThat(context.getSyncPromptContext()).isEmpty();
		assertThat(context.getCapabilities()).isEmpty();
	}

	@Test
	void invocationContextRequiresMethodAndRequest() {
		assertThatThrownBy(() -> AcpInvocationContext.builder().request(new Object()).build())
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("acpMethod");
		assertThatThrownBy(() -> AcpInvocationContext.builder().acpMethod("initialize").build())
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("request");
	}

}
