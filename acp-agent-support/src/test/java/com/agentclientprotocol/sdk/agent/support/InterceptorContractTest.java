/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interceptor contract around a failing call: what {@code onError} can answer, which
 * interceptors it reaches, what {@code afterCompletion} learns, and that every ACP method the
 * agent answers, the default {@code session/new} included, passes through the chain.
 */
class InterceptorContractTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final InMemoryTransportPair transportPair = InMemoryTransportPair.create();

	private AcpAgentSupport agentSupport;

	private AcpAsyncClient client;

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.closeGracefully().block(TIMEOUT);
		}
		if (agentSupport != null) {
			agentSupport.close();
		}
	}

	private AcpAsyncClient connect(AcpAgentSupport.Builder builder) {
		agentSupport = builder.transport(transportPair.agentTransport()).requestTimeout(TIMEOUT).build();
		agentSupport.start();
		client = AcpClient.async(transportPair.clientTransport()).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		return client;
	}

	private static AcpError failure(Runnable call) {
		try {
			call.run();
		}
		catch (AcpError e) {
			return e;
		}
		throw new AssertionError("the call did not fail with an AcpError");
	}

	@AcpAgent
	static class FailingAgent {

		@Prompt
		PromptResponse prompt(PromptRequest request) {
			throw new AcpProtocolException(AcpErrorCodes.RESOURCE_NOT_FOUND, "load failed");
		}

		@ExtRequest("_test/ok")
		Map<String, Object> ok() {
			return Map.of("ok", true);
		}

	}

	private String prompt(AcpAsyncClient client) {
		String sessionId = client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT).sessionId();
		client.prompt(PromptRequest.text(sessionId, "hi")).block(TIMEOUT);
		return sessionId;
	}

	@Test
	void anAcpProtocolExceptionThrownFromOnErrorIsTheAnswer() {
		AcpInterceptor mapper = new AcpInterceptor() {
			@Override
			public Object onError(AcpInvocationContext context, Throwable ex) {
				throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "mapped", Map.of("was", ex.getMessage()));
			}
		};
		AcpAsyncClient client = connect(AcpAgentSupport.create(new FailingAgent()).interceptor(mapper));

		AcpError error = failure(() -> prompt(client));

		assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INVALID_PARAMS);
		assertThat(error.getError().message()).isEqualTo("mapped");
		assertThat(error.getData()).isEqualTo(Map.of("was", "[-32002] load failed"));
	}

	@Test
	void anyOtherExceptionThrownFromOnErrorIsAnsweredAsAnInternalError() {
		AcpInterceptor broken = new AcpInterceptor() {
			@Override
			public Object onError(AcpInvocationContext context, Throwable ex) {
				throw new IllegalStateException("interceptor bug");
			}
		};
		AcpAsyncClient client = connect(AcpAgentSupport.create(new FailingAgent()).interceptor(broken));

		AcpError error = failure(() -> prompt(client));

		assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void anInterceptorThatThrowsFromOnErrorEndsTheWalk() {
		List<String> calls = new CopyOnWriteArrayList<>();
		AcpInterceptor outer = new Recording("outer", 0, calls);
		AcpInterceptor mapper = new Recording("mapper", 1, calls) {
			@Override
			public Object onError(AcpInvocationContext context, Throwable ex) {
				super.onError(context, ex);
				throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "mapped");
			}
		};
		AcpAsyncClient client = connect(AcpAgentSupport.create(new FailingAgent()).interceptor(outer).interceptor(mapper));

		assertThat(failure(() -> prompt(client)).getCode()).isEqualTo(AcpErrorCodes.INVALID_PARAMS);
		assertThat(calls).filteredOn(call -> call.startsWith("onError")).containsExactly("onError mapper");
	}

	@Test
	void onErrorSkipsInterceptorsWhosePreInvokeDidNotReturnTrue() {
		List<String> calls = new CopyOnWriteArrayList<>();
		AcpInterceptor first = new Recording("first", 0, calls);
		AcpInterceptor rejecting = new Recording("rejecting", 1, calls) {
			@Override
			public boolean preInvoke(AcpInvocationContext context) {
				super.preInvoke(context);
				if (AcpSchema.METHOD_SESSION_PROMPT.equals(context.getAcpMethod())) {
					throw new AcpProtocolException(AcpErrorCodes.INVALID_REQUEST, "rejected");
				}
				return true;
			}
		};
		AcpInterceptor last = new Recording("last", 2, calls);
		AcpAsyncClient client = connect(
				AcpAgentSupport.create(new FailingAgent()).interceptor(first).interceptor(rejecting).interceptor(last));
		String sessionId = client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT).sessionId();
		calls.clear();

		assertThat(failure(() -> client.prompt(PromptRequest.text(sessionId, "hi")).block(TIMEOUT)).getCode())
			.isEqualTo(AcpErrorCodes.INVALID_REQUEST);
		assertThat(calls).filteredOn(call -> call.startsWith("onError")).containsExactly("onError first");
		assertThat(calls).filteredOn(call -> call.startsWith("afterCompletion"))
			.containsExactly("afterCompletion first");
	}

	@Test
	void theDefaultNewSessionPassesThroughTheInterceptors() {
		List<String> calls = new CopyOnWriteArrayList<>();
		AcpAsyncClient client = connect(
				AcpAgentSupport.create(new FailingAgent()).interceptor(new Recording("log", 0, calls)));

		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

		assertThat(calls).contains("preInvoke log session/new", "afterCompletion log session/new");
	}

	@Test
	void anInterceptorCanVetoTheDefaultNewSession() {
		AcpInterceptor deny = new AcpInterceptor() {
			@Override
			public boolean preInvoke(AcpInvocationContext context) {
				if (AcpSchema.METHOD_SESSION_NEW.equals(context.getAcpMethod())) {
					throw new AcpProtocolException(AcpErrorCodes.AUTHENTICATION_REQUIRED, "log in first");
				}
				return true;
			}
		};
		AcpAsyncClient client = connect(AcpAgentSupport.create(new FailingAgent()).interceptor(deny));

		assertThat(failure(() -> client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT))
			.getCode()).isEqualTo(AcpErrorCodes.AUTHENTICATION_REQUIRED);
	}

	@Test
	void afterCompletionReceivesTheFailureOrNull() {
		Map<String, Object> endings = new ConcurrentHashMap<>();
		AcpInterceptor recorder = new AcpInterceptor() {
			@Override
			public void afterCompletion(AcpInvocationContext context, Throwable ex) {
				endings.put(context.getAcpMethod(), (ex != null) ? ex : "none");
			}
		};
		AcpAsyncClient client = connect(AcpAgentSupport.create(new FailingAgent()).interceptor(recorder));

		client.sendExtRequest("_test/ok", Map.of()).block(TIMEOUT);
		failure(() -> prompt(client));

		assertThat(endings).containsEntry("_test/ok", "none");
		assertThat(endings.get(AcpSchema.METHOD_SESSION_PROMPT)).isInstanceOf(AcpProtocolException.class)
			.hasFieldOrPropertyWithValue("code", AcpErrorCodes.RESOURCE_NOT_FOUND);
	}

	@Test
	void afterCompletionReceivesTheExceptionOnErrorThrew() {
		AcpProtocolException mapped = new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "mapped");
		Map<String, Object> endings = new ConcurrentHashMap<>();
		AcpInterceptor interceptor = new AcpInterceptor() {
			@Override
			public Object onError(AcpInvocationContext context, Throwable ex) {
				throw mapped;
			}

			@Override
			public void afterCompletion(AcpInvocationContext context, Throwable ex) {
				endings.put(context.getAcpMethod(), (ex != null) ? ex : "none");
			}
		};
		AcpAsyncClient client = connect(AcpAgentSupport.create(new FailingAgent()).interceptor(interceptor));

		failure(() -> prompt(client));

		assertThat(endings.get(AcpSchema.METHOD_SESSION_PROMPT)).isSameAs(mapped);
	}

	@Test
	void negativeDurationsAreRejectedByTheSetters() {
		AcpAgentSupport.Builder builder = AcpAgentSupport.create(new FailingAgent());
		assertThatThrownBy(() -> builder.cancelGracePeriod(Duration.ofSeconds(-1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("cancelGracePeriod");
		assertThatThrownBy(() -> builder.maxPromptDuration(Duration.ofSeconds(-1)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxPromptDuration");
		assertThatThrownBy(() -> builder.cancelGracePeriod(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> builder.maxPromptDuration(null)).isInstanceOf(IllegalArgumentException.class);
		assertThat(builder.cancelGracePeriod(Duration.ZERO).maxPromptDuration(Duration.ZERO).buildFactory())
			.isNotNull();
	}

	/** Records each step it sees as "step name method". */
	static class Recording implements AcpInterceptor {

		private final String name;

		private final int order;

		private final List<String> calls;

		Recording(String name, int order, List<String> calls) {
			this.name = name;
			this.order = order;
			this.calls = calls;
		}

		@Override
		public int getOrder() {
			return order;
		}

		@Override
		public boolean preInvoke(AcpInvocationContext context) {
			calls.add("preInvoke " + name + " " + context.getAcpMethod());
			return true;
		}

		@Override
		public Object onError(AcpInvocationContext context, Throwable ex) {
			calls.add("onError " + name);
			return null;
		}

		@Override
		public void afterCompletion(AcpInvocationContext context, Throwable ex) {
			calls.add("afterCompletion " + name + (AcpSchema.METHOD_SESSION_NEW.equals(context.getAcpMethod())
					? " " + context.getAcpMethod() : ""));
		}

	}

}
