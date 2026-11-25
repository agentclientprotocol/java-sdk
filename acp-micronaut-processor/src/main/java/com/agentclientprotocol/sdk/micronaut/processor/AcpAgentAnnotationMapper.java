/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.processor;

import java.lang.annotation.Annotation;
import java.util.List;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * Makes a class annotated {@code @AcpAgent} a Micronaut singleton bean, so the agent class needs no
 * {@code @Singleton} of its own and stays free of Micronaut: Micronaut's annotation processor adds
 * {@code jakarta.inject.Singleton} wherever it finds {@code @AcpAgent}, at compile time, and
 * {@code acp-micronaut} then serves the bean. The annotation is named, not imported, so the
 * processor path needs no {@code acp-annotations}.
 *
 * <p>Micronaut finds mappers on the annotation processor path only. Add this module there, beside
 * {@code micronaut-inject-java}, in every module whose sources hold the {@code @AcpAgent} class;
 * the application's runtime dependencies are unchanged. An agent class compiled without Micronaut's
 * processors, in a shared module or a library jar, is imported instead:
 * {@code @Import(packages = "...")} on one of the application's classes, which is then compiled
 * with this module on its processor path.
 *
 * @author Mark Pollack
 */
public final class AcpAgentAnnotationMapper implements NamedAnnotationMapper {

	/** The annotation mapped: {@value}. */
	static final String ACP_AGENT = "com.agentclientprotocol.sdk.annotation.AcpAgent";

	/** The scope it maps to: {@value}. */
	static final String SINGLETON = "jakarta.inject.Singleton";

	@Override
	public String getName() {
		return ACP_AGENT;
	}

	@Override
	public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
		return List.of(AnnotationValue.builder(SINGLETON).build());
	}

}
