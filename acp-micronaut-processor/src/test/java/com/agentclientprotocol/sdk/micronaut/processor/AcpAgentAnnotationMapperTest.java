/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.processor;

import java.util.ServiceLoader;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.AnnotationMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapper maps {@code @AcpAgent} to {@code @Singleton} and is registered for Micronaut's
 * processor to find. That the application then gets the bean is tested in acp-micronaut, whose test
 * sources are compiled with this processor.
 */
class AcpAgentAnnotationMapperTest {

	@Test
	void mapsAcpAgentToSingleton() {
		AcpAgentAnnotationMapper mapper = new AcpAgentAnnotationMapper();

		assertThat(mapper.getName()).isEqualTo("com.agentclientprotocol.sdk.annotation.AcpAgent");
		assertThat(mapper.map(AnnotationValue.builder(mapper.getName()).build(), null))
			.extracting(AnnotationValue::getAnnotationName)
			.containsExactly("jakarta.inject.Singleton");
	}

	@Test
	@SuppressWarnings("rawtypes")
	void isRegisteredForTheProcessor() {
		assertThat(ServiceLoader.load(AnnotationMapper.class).stream().map(ServiceLoader.Provider::type))
			.contains(AcpAgentAnnotationMapper.class);
	}

}
