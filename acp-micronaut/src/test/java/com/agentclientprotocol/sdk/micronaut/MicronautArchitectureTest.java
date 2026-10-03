/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.json.JacksonAcpJsonMapper;
import com.agentclientprotocol.sdk.micronaut.agent.AcpAgentRuntime;
import com.agentclientprotocol.sdk.spec.AcpAgentSession;
import com.agentclientprotocol.sdk.spec.AcpClientSession;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasModifiers.Predicates.modifier;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * acp-micronaut is a thin client of the SDK's public API, and the SDK never depends on
 * Micronaut.
 *
 * <pre>
 *        agent        client
 *            \         /
 *        micronaut (TransportType)
 * </pre>
 */
class MicronautArchitectureTest {

	private static final String MICRONAUT_MODULE = "com.agentclientprotocol.sdk.micronaut..";

	/** This module's main classes (the bean definitions Micronaut generated included). */
	private static final JavaClasses MODULE = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importLocations(java.util.Set.of(location(AcpAgentRuntime.class)));

	/** The SDK modules this one builds on: annotations, core, JSON, annotation support, Jetty. */
	private static final JavaClasses SDK = new ClassFileImporter()
		.importLocations(Stream.of(AcpAgent.class, AcpSchema.class, JacksonAcpJsonMapper.class, AcpAgentSupport.class,
				StreamableHttpAcpAgentTransport.class)
			.map(MicronautArchitectureTest::location)
			.collect(java.util.stream.Collectors.toSet()));

	@Test
	void importsThisModuleOnly() {
		assertThat(MODULE.contain(AcpAgentRuntime.class)).isTrue();
		assertThat(MODULE).allMatch(type -> type.getPackageName().startsWith("com.agentclientprotocol.sdk.micronaut"),
				"in this module's packages");
		assertThat(SDK.contain(AcpAgentSupport.class)).isTrue();
	}

	@Test
	void theSdkNeverDependsOnMicronaut() {
		noClasses().that()
			.resideOutsideOfPackage(MICRONAUT_MODULE)
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage("io.micronaut..", MICRONAUT_MODULE)
			.because("the SDK is framework-neutral: Micronaut is reached only from acp-micronaut")
			.check(SDK);
	}

	@Test
	void noPackageCycles() {
		slices().matching("com.agentclientprotocol.sdk.(**)").should().beFreeOfCycles().check(MODULE);
	}

	@Test
	void agentAndClientAreIndependent() {
		noClasses().that()
			.resideInAPackage("..micronaut.agent..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("..micronaut.client..")
			.check(MODULE);
		noClasses().that()
			.resideInAPackage("..micronaut.client..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("..micronaut.agent..")
			.check(MODULE);
	}

	@Test
	void usesOnlyThePublicSdkApi() {
		DescribedPredicate<JavaClass> internals = resideInAPackage("com.agentclientprotocol.sdk..")
			.and(not(resideInAPackage(MICRONAUT_MODULE)))
			.and(belongToAnyOf(AcpAgentSession.class, AcpClientSession.class).or(not(modifier(JavaModifier.PUBLIC))))
			.as("SDK internals (session implementations, non-public classes)");
		noClasses().should()
			.dependOnClassesThat(internals)
			.because("the integration is a client of the SDK's public API, like any application")
			.check(MODULE);
	}

	/** The directory or jar a class was loaded from. */
	private static Location location(Class<?> type) {
		Path root = root(type);
		try {
			return Files.isDirectory(root) ? Location.of(root) : Location.of(new JarFile(root.toFile()));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static Path root(Class<?> type) {
		try {
			return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
		}
		catch (URISyntaxException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
