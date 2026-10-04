/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * acp-integration is framework-neutral, and the SDK below it does not know it exists.
 *
 * <pre>
 *   spring-boot-autoconfigure   micronaut   quarkus
 *                  \               |         /
 *                       acp-integration
 *                      /               \
 *               acp-agent-support    acp-core
 * </pre>
 */
class IntegrationArchitectureTest {

	private static final String INTEGRATION = "com.agentclientprotocol.sdk.integration..";

	/** This module's main classes. */
	private static final JavaClasses MODULE = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importLocations(Set.of(location(AcpHost.class)));

	/** The SDK modules below this one: annotations, core, annotation support. */
	private static final JavaClasses SDK = new ClassFileImporter().importLocations(
			Stream.of(AcpAgent.class, AcpSchema.class, AcpAgentSupport.class)
				.map(IntegrationArchitectureTest::location)
				.collect(Collectors.toSet()));

	@Test
	void importsThisModuleOnly() {
		assertThat(MODULE.contain(AcpHost.class)).isTrue();
		assertThat(MODULE).allMatch(type -> type.getPackageName().equals("com.agentclientprotocol.sdk.integration"),
				"in this module's package");
		assertThat(SDK.contain(AcpAgentSupport.class)).isTrue();
	}

	@Test
	void dependsOnNoFramework() {
		noClasses().should()
			.dependOnClassesThat()
			.resideInAnyPackage("org.springframework..", "io.quarkus..", "io.micronaut..", "io.vertx..",
					"jakarta.enterprise..", "io.smallrye..")
			.because("acp-integration is the framework-neutral half of every framework integration")
			.check(MODULE);
	}

	@Test
	void theSdkBelowDoesNotDependOnIt() {
		noClasses().should()
			.dependOnClassesThat()
			.resideInAPackage(INTEGRATION)
			.because("acp-core and acp-agent-support are below the framework integrations")
			.check(SDK);
	}

	/** The directory or jar a class was loaded from. */
	private static Location location(Class<?> type) {
		Path root;
		try {
			root = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
		}
		catch (URISyntaxException ex) {
			throw new IllegalStateException(ex);
		}
		try {
			return Files.isDirectory(root) ? Location.of(root) : Location.of(new JarFile(root.toFile()));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
