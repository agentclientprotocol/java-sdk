/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is specific to the Jackson 2 mapper: its construction, the lenient default
 * {@link JacksonAcpJsonMapper#defaultObjectMapper()} with DEBUG drift logging, and that a
 * consumer's strict {@link ObjectMapper} is honoured. The portable contract is
 * {@link AcpJsonMapperContractTest}.
 */
class JacksonAcpJsonMapperTest {

	@Test
	void nullObjectMapperThrows() {
		assertThatThrownBy(() -> new JacksonAcpJsonMapper(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("ObjectMapper must not be null");
	}

	@Test
	void getObjectMapperReturnsInstance() {
		ObjectMapper om = new ObjectMapper();
		assertThat(new JacksonAcpJsonMapper(om).getObjectMapper()).isSameAs(om);
	}

	@Test
	void serviceLoaderFindsTheJacksonSupplier() {
		assertThat(AcpJsonMapper.createDefault()).isInstanceOf(JacksonAcpJsonMapper.class);
		assertThat(new JacksonAcpJsonMapperSupplier().priority()).isEqualTo(JacksonAcpJsonMapperSupplier.PRIORITY);
	}

	@Test
	void defaultObjectMapperIsANewInstanceEachTime() {
		assertThat(JacksonAcpJsonMapper.defaultObjectMapper()).isNotSameAs(JacksonAcpJsonMapper.defaultObjectMapper());
	}

	@Test
	void defaultMapperLogsEachIgnoredPropertyAtDebug() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(JacksonAcpJsonMapper.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level previous = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			new JacksonAcpJsonMapperSupplier().get().readValue(StrictMapperFixtures.CAPABILITIES_WITH_DRIFT, AcpSchema.AgentCapabilities.class);
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
	void strictMapperSuppliedByTheConsumerFailsOnUnknownFields() {
		ObjectMapper strict = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
		AcpJsonMapper mapper = new JacksonAcpJsonMapper(strict);

		assertThatThrownBy(() -> mapper.readValue(StrictMapperFixtures.CAPABILITIES_WITH_DRIFT, AcpSchema.AgentCapabilities.class))
			.isInstanceOf(UnrecognizedPropertyException.class)
			.hasMessageContaining("fabricatedFeature");
	}

	@Test
	void strictMapperStillAcceptsEverySpecField() throws Exception {
		ObjectMapper strict = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
		AcpJsonMapper mapper = new JacksonAcpJsonMapper(strict);

		AcpSchema.InitializeResponse response = mapper.readValue(StrictMapperFixtures.ON_SPEC_INITIALIZE_RESPONSE,
				AcpSchema.InitializeResponse.class);

		assertThat(response.agentCapabilities().promptCapabilities().image()).isTrue();
		assertThat(response.meta()).containsEntry("k", "v");
	}

}
