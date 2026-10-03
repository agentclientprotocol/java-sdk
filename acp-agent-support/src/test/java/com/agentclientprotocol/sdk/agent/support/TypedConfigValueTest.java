/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.ConfigId;
import com.agentclientprotocol.sdk.annotation.ConfigValue;
import com.agentclientprotocol.sdk.annotation.SessionId;
import com.agentclientprotocol.sdk.annotation.SetSessionConfigOption;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A {@code @SetSessionConfigOption} method takes the option id and the value, typed by its
 * parameter, instead of unpacking the raw request: {@code @ConfigValue String} for a select
 * option, {@code @ConfigValue boolean} for a boolean one, {@code @ConfigValue Object} for either.
 */
class TypedConfigValueTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@AcpAgent
	static class SelectAgent {

		final Map<String, Object> received = new ConcurrentHashMap<>();

		@com.agentclientprotocol.sdk.annotation.NewSession
		com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse newSession() {
			return new com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse("s1", null, null);
		}

		@SetSessionConfigOption
		SetSessionConfigOptionResponse set(@SessionId String session, @ConfigId String id, @ConfigValue String value) {
			received.put(session + "/" + id, value);
			return new SetSessionConfigOptionResponse(List.of());
		}

	}

	@AcpAgent
	static class BooleanAgent {

		final Map<String, Object> received = new ConcurrentHashMap<>();

		@com.agentclientprotocol.sdk.annotation.NewSession
		com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse newSession() {
			return new com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse("s1", null, null);
		}

		@SetSessionConfigOption
		SetSessionConfigOptionResponse set(@ConfigId String id, @ConfigValue boolean value) {
			received.put(id, value);
			return new SetSessionConfigOptionResponse(List.of());
		}

	}

	@AcpAgent
	static class RawAgent {

		final Map<String, Object> received = new ConcurrentHashMap<>();

		@com.agentclientprotocol.sdk.annotation.NewSession
		com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse newSession() {
			return new com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse("s1", null, null);
		}

		@SetSessionConfigOption
		SetSessionConfigOptionResponse set(@ConfigId String id, @ConfigValue Object value) {
			received.put(id, value);
			return new SetSessionConfigOptionResponse(List.of());
		}

	}

	@Test
	void aSelectValueArrivesAsAString() {
		SelectAgent agent = new SelectAgent();
		run(agent, client -> client.setSessionConfigOption(SetSessionConfigOptionRequest.select("s1", "model", "fast"))
			.block(TIMEOUT));
		assertThat(agent.received).containsExactly(Map.entry("s1/model", "fast"));
	}

	@Test
	void aBooleanValueArrivesAsABoolean() {
		BooleanAgent agent = new BooleanAgent();
		run(agent, client -> client.setSessionConfigOption(SetSessionConfigOptionRequest.bool("s1", "verbose", true))
			.block(TIMEOUT));
		assertThat(agent.received).containsExactly(Map.entry("verbose", true));
	}

	@Test
	void anObjectParameterTakesEitherKind() {
		RawAgent agent = new RawAgent();
		run(agent, client -> {
			client.setSessionConfigOption(SetSessionConfigOptionRequest.bool("s1", "verbose", false)).block(TIMEOUT);
			client.setSessionConfigOption(SetSessionConfigOptionRequest.select("s1", "model", "deep")).block(TIMEOUT);
		});
		assertThat(agent.received).containsOnly(Map.entry("verbose", false), Map.entry("model", "deep"));
	}

	@Test
	void aValueOfTheOtherKindIsInvalidParamsAndTheMethodIsNotCalled() {
		BooleanAgent booleans = new BooleanAgent();
		run(booleans, client -> assertThatThrownBy(() -> client
			.setSessionConfigOption(SetSessionConfigOptionRequest.select("s1", "verbose", "yes"))
			.block(TIMEOUT)).isInstanceOfSatisfying(AcpError.class,
					error -> assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INVALID_PARAMS)));
		assertThat(booleans.received).isEmpty();

		SelectAgent selects = new SelectAgent();
		run(selects, client -> assertThatThrownBy(() -> client
			.setSessionConfigOption(SetSessionConfigOptionRequest.bool("s1", "model", true))
			.block(TIMEOUT)).isInstanceOfSatisfying(AcpError.class,
					error -> assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INVALID_PARAMS)));
		assertThat(selects.received).isEmpty();
	}

	private static void run(Object bean, Consumer<AcpAsyncClient> scenario) {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport agent = AcpAgentSupport.create(bean)
			.transport(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.build();
		agent.start();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize().block(TIMEOUT);
			scenario.accept(client);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.close();
		}
	}

}
