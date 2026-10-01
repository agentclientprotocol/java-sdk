/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json.jackson3;

import java.io.IOException;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.StrictMapperFixtures;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is specific to the Jackson 3 mapper: its construction, the lenient default
 * {@link Jackson3AcpJsonMapper#defaultJsonMapper()} with DEBUG drift logging, and that a
 * consumer's strict {@link JsonMapper} is honoured. The portable contract and the wire
 * format are checked by the acp-core suite this module also runs.
 */
class Jackson3AcpJsonMapperTest {

	private static JsonMapper strictMapper() {
		return JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
	}

	@Test
	void nullJsonMapperThrows() {
		assertThatThrownBy(() -> new Jackson3AcpJsonMapper(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("JsonMapper must not be null");
	}

	@Test
	void getJsonMapperReturnsInstance() {
		JsonMapper jm = JsonMapper.builder().build();
		assertThat(new Jackson3AcpJsonMapper(jm).getJsonMapper()).isSameAs(jm);
	}

	@Test
	void serviceLoaderFindsTheJackson3Supplier() {
		assertThat(AcpJsonMapper.createDefault()).isInstanceOf(Jackson3AcpJsonMapper.class);
		assertThat(new Jackson3AcpJsonMapperSupplier().priority()).isEqualTo(Jackson3AcpJsonMapperSupplier.PRIORITY);
	}

	@Test
	void defaultJsonMapperIsANewInstanceEachTime() {
		assertThat(Jackson3AcpJsonMapper.defaultJsonMapper()).isNotSameAs(Jackson3AcpJsonMapper.defaultJsonMapper());
	}

	@Test
	void defaultMapperLogsEachIgnoredPropertyAtDebug() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(Jackson3AcpJsonMapper.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level previous = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			new Jackson3AcpJsonMapperSupplier().get()
				.readValue(StrictMapperFixtures.CAPABILITIES_WITH_DRIFT, AcpSchema.AgentCapabilities.class);
		}
		finally {
			logger.detachAppender(appender);
			logger.setLevel(previous);
		}

		List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		assertThat(messages).containsExactly(
				"Ignoring unknown property 'fabricatedFeature' on AgentCapabilities (the peer is newer than this SDK, or off-spec)");
		assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
	}

	@Test
	void rebuiltDefaultKeepsTheDriftLogging() throws Exception {
		JsonMapper rebuilt = Jackson3AcpJsonMapper.defaultJsonMapper().rebuild().build();
		AcpSchema.AgentCapabilities caps = new Jackson3AcpJsonMapper(rebuilt)
			.readValue(StrictMapperFixtures.CAPABILITIES_WITH_DRIFT, AcpSchema.AgentCapabilities.class);
		assertThat(caps.loadSession()).isTrue();
	}

	@Test
	void strictMapperSuppliedByTheConsumerFailsOnUnknownFields() {
		AcpJsonMapper mapper = new Jackson3AcpJsonMapper(strictMapper());

		assertThatThrownBy(
				() -> mapper.readValue(StrictMapperFixtures.CAPABILITIES_WITH_DRIFT, AcpSchema.AgentCapabilities.class))
			.isInstanceOf(IOException.class)
			.hasCauseInstanceOf(UnrecognizedPropertyException.class)
			.hasMessageContaining("fabricatedFeature");
	}

	@Test
	void strictMapperStillAcceptsEverySpecField() throws Exception {
		AcpJsonMapper mapper = new Jackson3AcpJsonMapper(strictMapper());

		AcpSchema.InitializeResponse response = mapper.readValue(StrictMapperFixtures.ON_SPEC_INITIALIZE_RESPONSE,
				AcpSchema.InitializeResponse.class);

		assertThat(response.agentCapabilities().promptCapabilities().image()).isTrue();
		assertThat(response.meta()).containsEntry("k", "v");
	}

	/**
	 * Why {@link Jackson3AcpJsonMapper#defaultJsonMapper()} is not Jackson 3's own default,
	 * part 1: a bare Jackson 3 mapper sorts properties alphabetically. Record components
	 * keep their order either way (creator properties come first), but any other value a
	 * message carries, such as an application object in {@code rawInput} or
	 * {@code params}, would be reordered relative to Jackson 2.
	 */
	@Test
	void defaultKeepsDeclarationOrderWhereBareJackson3Sorts() throws Exception {
		Payload payload = new Payload();

		assertThat(JsonMapper.builder().build().writeValueAsString(payload)).isEqualTo("{\"alpha\":2,\"zulu\":1}");
		assertThat(new Jackson3AcpJsonMapperSupplier().get().writeValueAsString(payload))
			.isEqualTo("{\"zulu\":1,\"alpha\":2}");
	}

	/**
	 * Part 2: a bare Jackson 3 mapper rejects content after the first value and a
	 * {@code null} for a primitive; Jackson 2, and so the SDK, accept both.
	 */
	@Test
	void defaultAcceptsWhatJackson2AcceptsWhereBareJackson3Fails() throws Exception {
		String trailing = "{\"code\":1,\"message\":\"m\"} trailing";
		String nullPrimitive = "{\"code\":null,\"message\":\"m\"}";
		AcpJsonMapper bare = new Jackson3AcpJsonMapper(JsonMapper.builder().build());
		AcpJsonMapper sdk = new Jackson3AcpJsonMapperSupplier().get();

		assertThatThrownBy(() -> bare.readValue(trailing, AcpSchema.JSONRPCError.class)).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> bare.readValue(nullPrimitive, AcpSchema.JSONRPCError.class))
			.isInstanceOf(IOException.class);
		assertThat(sdk.readValue(trailing, AcpSchema.JSONRPCError.class).code()).isEqualTo(1);
		assertThat(sdk.readValue(nullPrimitive, AcpSchema.JSONRPCError.class).code()).isZero();
	}

	/** Fields declared out of alphabetical order. */
	public static class Payload {

		public int zulu = 1;

		public int alpha = 2;

	}

}
