/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The annotation model marks the same protocol methods unstable as acp-core does (where
 * {@code UnstableApiMarkerTest} derives them from the stable schema): a handler annotation is
 * marked {@link UnstableAcpApi} exactly when the agent builder's setter for its method is, and an
 * argument resolver that reads an unstable request type is marked. The marker has class
 * retention, so it is read from the bytecode.
 */
class UnstableAnnotationSupportTest {

	private static final JavaClasses CLASSES = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("com.agentclientprotocol.sdk");

	@Test
	void handlerAnnotationsAreMarkedExactlyWhenTheirBuilderSetterIs() {
		JavaClass builder = CLASSES.get(AcpAgent.SyncAgentBuilder.class);
		List<String> violations = new ArrayList<>();
		int unstable = 0;
		for (Map.Entry<Class<? extends java.lang.annotation.Annotation>, String> entry : AcpAgentSupport
			.handlerAnnotations()
			.entrySet()) {
			String name = entry.getKey().getSimpleName();
			String setter = Character.toLowerCase(name.charAt(0)) + name.substring(1) + "Handler";
			boolean setterMarked = builder.getMethods()
				.stream()
				.filter(method -> method.getName().equals(setter))
				.anyMatch(method -> method.isAnnotatedWith(UnstableAcpApi.class));
			unstable += setterMarked ? 1 : 0;
			if (CLASSES.get(entry.getKey()).isAnnotatedWith(UnstableAcpApi.class) != setterMarked) {
				violations.add("@" + name + " (" + entry.getValue() + ")" + (setterMarked ? " is not marked" : " is marked"));
			}
		}
		assertThat(unstable).as("unstable handler setters").isPositive();
		assertThat(violations).isEmpty();
	}

	@Test
	void resolversOfUnstableRequestTypesAreMarked() {
		List<String> violations = new ArrayList<>();
		for (JavaClass resolver : CLASSES) {
			if (!resolver.isAssignableTo(ArgumentResolver.class) || resolver.isInterface()
					|| resolver.isAnnotatedWith(UnstableAcpApi.class)) {
				continue;
			}
			resolver.getDirectDependenciesFromSelf()
				.stream()
				.map(dependency -> dependency.getTargetClass())
				.filter(target -> target.isAnnotatedWith(UnstableAcpApi.class))
				.findFirst()
				.ifPresent(target -> violations.add(resolver.getSimpleName() + " reads " + target.getSimpleName()));
		}
		assertThat(violations).isEmpty();
	}

}
