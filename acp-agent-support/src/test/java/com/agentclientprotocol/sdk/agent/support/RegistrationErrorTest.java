/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.ConfigValue;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import com.agentclientprotocol.sdk.annotation.ListSessions;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.SessionId;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Misusing the annotations fails when the agent is built, not at the first request, with a
 * message that names the class, the method and the fix. One test per message.
 */
class RegistrationErrorTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@AcpAgent
	static class UnresolvableParameter {

		@Prompt
		PromptResponse prompt(Integer unresolvable) {
			return PromptResponse.endTurn();
		}

	}

	@Test
	void aParameterNoResolverHandles() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new UnresolvableParameter()).buildFactory())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("UnresolvableParameter.prompt")
			.hasMessageContaining("java.lang.Integer")
			.hasMessageContaining("ArgumentResolver");
	}

	/** A custom resolver registered for the type makes the same method valid. */
	@Test
	void aCustomResolverMakesTheParameterResolvable() {
		ArgumentResolver integers = new ArgumentResolver() {
			@Override
			public boolean supportsParameter(AcpMethodParameter parameter) {
				return parameter.getParameterType() == Integer.class;
			}

			@Override
			public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
				return 7;
			}
		};
		assertThat(AcpAgentSupport.create(new UnresolvableParameter()).argumentResolver(integers).buildFactory())
			.isNotNull();
	}

	@AcpAgent
	static class OtherMethodsRequest {

		@Prompt
		PromptResponse prompt(NewSessionRequest wrongRequestType) {
			return PromptResponse.endTurn();
		}

	}

	@Test
	void aRequestTypeOfAnotherMethod() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new OtherMethodsRequest()).buildFactory())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("OtherMethodsRequest.prompt")
			.hasMessageContaining("NewSessionRequest")
			.hasMessageContaining("PromptRequest");
	}

	@AcpAgent
	static class SessionIdWithoutASession {

		@ListSessions
		AcpSchema.ListSessionsResponse list(@SessionId String sessionId) {
			return new AcpSchema.ListSessionsResponse(List.of());
		}

	}

	@Test
	void aSessionIdOnAMethodWithoutASession() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new SessionIdWithoutASession()).buildFactory())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("SessionIdWithoutASession.list")
			.hasMessageContaining("@SessionId")
			.hasMessageContaining("session/list");
	}

	@AcpAgent
	static class SessionIdOnAnExtension {

		@ExtRequest("_example/ping")
		Map<String, Object> ping(Map<String, Object> params, @SessionId String sessionId) {
			return Map.of();
		}

	}

	/** An extension's @SessionId parameter used to receive the params, and every call failed -32602. */
	@Test
	void aSessionIdOnAnExtensionMethod() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new SessionIdOnAnExtension()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("SessionIdOnAnExtension.ping")
			.hasMessageContaining("@SessionId");
	}

	@AcpAgent
	static class PromptContextElsewhere {

		@NewSession
		NewSessionResponse newSession(PromptContext context) {
			return new NewSessionResponse("s", null, null);
		}

	}

	@Test
	void aPromptContextOutsideAPromptMethod() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new PromptContextElsewhere()).buildFactory())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("PromptContextElsewhere.newSession")
			.hasMessageContaining("PromptContext")
			.hasMessageContaining("@Prompt");
	}

	@AcpAgent
	static class ConfigValueElsewhere {

		@NewSession
		NewSessionResponse newSession(@ConfigValue String value) {
			return new NewSessionResponse("s", null, null);
		}

	}

	@Test
	void aConfigValueOutsideASetConfigOptionMethod() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new ConfigValueElsewhere()).buildFactory())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ConfigValueElsewhere.newSession")
			.hasMessageContaining("@ConfigValue")
			.hasMessageContaining("@SetSessionConfigOption");
	}

	@AcpAgent
	static class UnsupportedReturnType {

		@NewSession
		Integer newSession(NewSessionRequest request) {
			return 42;
		}

	}

	@Test
	void aReturnTypeNoHandlerAccepts() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new UnsupportedReturnType()).buildFactory())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("UnsupportedReturnType.newSession")
			.hasMessageContaining("java.lang.Integer")
			.hasMessageContaining("NewSessionResponse");
	}

	@AcpAgent
	static class OtherMethodsResponse {

		@NewSession
		InitializeResponse newSession(NewSessionRequest request) {
			return InitializeResponse.ok();
		}

	}

	@Test
	void aResponseTypeOfAnotherMethod() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new OtherMethodsResponse()).buildFactory())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("OtherMethodsResponse.newSession")
			.hasMessageContaining("InitializeResponse")
			.hasMessageContaining("NewSessionResponse");
	}

	@AcpAgent
	static class MissingReturn {

		@NewSession
		void newSession(NewSessionRequest request) {
		}

	}

	@Test
	void aMissingReturnWhereAResponseIsRequired() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new MissingReturn()).buildFactory())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("MissingReturn.newSession")
			.hasMessageContaining("void")
			.hasMessageContaining("NewSessionResponse");
	}

	@AcpAgent
	static class TwoPromptMethods {

		@Prompt
		PromptResponse first() {
			return PromptResponse.endTurn();
		}

		@Prompt
		PromptResponse second() {
			return PromptResponse.refusal();
		}

	}

	/** One of the two used to win silently. */
	@Test
	void twoMethodsForOneHandler() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new TwoPromptMethods()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("@Prompt")
			.hasMessageContaining("first")
			.hasMessageContaining("second")
			.hasMessageContaining(TwoPromptMethods.class.getSimpleName());
	}

	@AcpAgent
	static class TwoAnnotationsOnOneMethod {

		@NewSession
		@Prompt
		NewSessionResponse both() {
			return new NewSessionResponse("s", null, null);
		}

	}

	@Test
	void oneMethodWithTwoHandlerAnnotations() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new TwoAnnotationsOnOneMethod()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("TwoAnnotationsOnOneMethod.both")
			.hasMessageContaining("@NewSession")
			.hasMessageContaining("@Prompt");
	}

	@AcpAgent
	static class PrivateAndStaticHandlers {

		@NewSession
		private static NewSessionResponse newSession() {
			return new NewSessionResponse("s1", null, null);
		}

		@Prompt
		private PromptResponse prompt() {
			return PromptResponse.refusal();
		}

	}

	/** Visibility and static are not misuse: such handlers are served. */
	@Test
	void privateAndStaticHandlersAreServed() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport agent = AcpAgentSupport.create(new PrivateAndStaticHandlers())
			.transport(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.build();
		agent.start();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize().block(TIMEOUT);
			String session = client.newSession(new NewSessionRequest("/w", List.of())).block(TIMEOUT).sessionId();
			assertThat(client.prompt(new AcpSchema.PromptRequest(session, List.of(new AcpSchema.TextContent("x"))))
				.block(TIMEOUT)
				.stopReason()).isEqualTo(AcpSchema.StopReason.REFUSAL);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.close();
		}
	}

}
