/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.LocationProvider;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * acp-quarkus-deployment: build steps only. They read the index and the build-time
 * configuration and register the runtime beans; they never call the SDK's runtime API
 * (agents, clients, transports), which runs in the application, not in the build.
 */
@AnalyzeClasses(locations = DeploymentArchitectureTest.ThisModule.class,
		importOptions = ImportOption.DoNotIncludeTests.class)
class DeploymentArchitectureTest {

	@ArchTest
	static void importsThisModule(JavaClasses classes) {
		assertThat(classes.contain(AcpProcessor.class)).isTrue();
	}

	@ArchTest
	static final ArchRule buildStepsNeverRunTheSdk = noClasses().should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.agentclientprotocol.sdk.agent..", "com.agentclientprotocol.sdk.client..",
				"com.agentclientprotocol.sdk.spec..", "org.eclipse.jetty..")
		.because("the SDK runs in the application; the build only finds the agent and registers beans");

	/** Imports the directory or jar holding this module's main classes. */
	public static final class ThisModule implements LocationProvider {

		@Override
		public Set<Location> get(Class<?> testClass) {
			try {
				Path root = Path.of(AcpProcessor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
				return Set.of(Files.isDirectory(root) ? Location.of(root) : Location.of(new JarFile(root.toFile())));
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			catch (URISyntaxException ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

}
