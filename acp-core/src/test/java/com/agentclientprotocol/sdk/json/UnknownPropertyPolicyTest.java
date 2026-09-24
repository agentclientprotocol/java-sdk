/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unknown-field policy (#10): the SDK is lenient by default so that a peer newer than this
 * SDK keeps working, and that leniency lives in the mapper, not in the schema records. A
 * consumer who supplies a strict {@link ObjectMapper} therefore gets strict behaviour.
 */
class UnknownPropertyPolicyTest {

	// The reporter's reproducer: an agent that advertises a capability this SDK does not know.
	private static final String CAPABILITIES_WITH_DRIFT = """
			{"loadSession": true, "fabricatedFeature": true}
			""";

	@Test
	void defaultMapperToleratesUnknownFieldsAndKeepsKnownOnes() throws Exception {
		AcpJsonMapper mapper = AcpJsonMapper.createDefault();

		AcpSchema.AgentCapabilities caps = mapper.readValue(CAPABILITIES_WITH_DRIFT, AcpSchema.AgentCapabilities.class);

		assertThat(caps.loadSession()).isTrue();
		assertThat(caps.meta()).as("unknown fields are not smuggled into _meta").isNull();
	}

	@Test
	void strictMapperSuppliedByTheConsumerFailsOnUnknownFields() {
		ObjectMapper strict = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
		AcpJsonMapper mapper = new JacksonAcpJsonMapper(strict);

		assertThatThrownBy(() -> mapper.readValue(CAPABILITIES_WITH_DRIFT, AcpSchema.AgentCapabilities.class))
			.isInstanceOf(UnrecognizedPropertyException.class)
			.hasMessageContaining("fabricatedFeature");
	}

	@Test
	void strictMapperStillAcceptsEverySpecField() throws Exception {
		ObjectMapper strict = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
		AcpJsonMapper mapper = new JacksonAcpJsonMapper(strict);

		String onSpec = """
				{"protocolVersion": 1, "agentCapabilities": {"loadSession": true,
				 "promptCapabilities": {"image": true, "audio": false, "embeddedContext": true}},
				 "authMethods": [], "agentInfo": {"name": "x", "version": "1"}, "_meta": {"k": "v"}}
				""";
		AcpSchema.InitializeResponse response = mapper.readValue(onSpec, AcpSchema.InitializeResponse.class);

		assertThat(response.agentCapabilities().promptCapabilities().image()).isTrue();
		assertThat(response.meta()).containsEntry("k", "v");
	}

	@Test
	void defaultObjectMapperIsANewInstanceEachTime() {
		assertThat(JacksonAcpJsonMapper.defaultObjectMapper()).isNotSameAs(JacksonAcpJsonMapper.defaultObjectMapper());
	}

}
