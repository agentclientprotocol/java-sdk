/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;

import com.agentclientprotocol.sdk.spring.boot.autoconfigure.client.AcpClientAutoConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.LocationProvider;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * acp-spring-boot-autoconfigure: Spring Boot autoconfiguration over the SDK's public API.
 *
 * <pre>
 *        client          agent
 *            \           /
 *         acp-integration
 * </pre>
 *
 * <p>
 * The client and agent sides are independent: a client-only application loads nothing of
 * the agent side, and the reverse. The module builds on the SDK's public types and does
 * not reach into other framework integrations.
 * </p>
 */
@AnalyzeClasses(locations = SpringBootAutoconfigureArchitectureTest.ThisModule.class,
		importOptions = ImportOption.DoNotIncludeTests.class)
class SpringBootAutoconfigureArchitectureTest {

	@ArchTest
	static void importsThisModuleAndNothingElse(JavaClasses classes) {
		assertThat(classes.contain(ThisModule.ANCHOR)).as("this module's classes are imported").isTrue();
		assertThat(classes).allSatisfy(javaClass -> assertThat(javaClass.getPackageName())
			.startsWith("com.agentclientprotocol.sdk.spring.boot.autoconfigure"));
	}

	@ArchTest
	static final ArchRule noPackageCycles = slices()
		.matching("com.agentclientprotocol.sdk.spring.boot.autoconfigure.(**)")
		.should()
		.beFreeOfCycles();

	@ArchTest
	static final ArchRule clientDoesNotDependOnAgent = noClasses().that()
		.resideInAPackage("..autoconfigure.client..")
		.should()
		.dependOnClassesThat(resideInAnyPackage("..autoconfigure.agent..", "com.agentclientprotocol.sdk.agent.."))
		.because("a client-only application must not need the agent side");

	@ArchTest
	static final ArchRule agentDoesNotDependOnClientSide = noClasses().that()
		.resideInAPackage("..autoconfigure.agent..")
		.should()
		.dependOnClassesThat(resideInAnyPackage("..autoconfigure.client.."))
		.because("the agent autoconfiguration is independent of the client autoconfiguration");

	@ArchTest
	static final ArchRule usesNoOtherFramework = noClasses().should()
		.dependOnClassesThat(resideInAnyPackage("io.quarkus..", "io.micronaut.."))
		.because("each framework integration builds on the SDK alone");

	/**
	 * Imports the directory or jar holding this module's main classes, found from {@code ANCHOR}, so
	 * that the rules see this module only.
	 */
	public static final class ThisModule implements LocationProvider {

		static final Class<?> ANCHOR = AcpClientAutoConfiguration.class;

		@Override
		public Set<Location> get(Class<?> testClass) {
			Path root = root();
			try {
				return Set.of(Files.isDirectory(root) ? Location.of(root) : Location.of(new JarFile(root.toFile())));
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		private static Path root() {
			try {
				return Path.of(ANCHOR.getProtectionDomain().getCodeSource().getLocation().toURI());
			}
			catch (URISyntaxException ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

}
