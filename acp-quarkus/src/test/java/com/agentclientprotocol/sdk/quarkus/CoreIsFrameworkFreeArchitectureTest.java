/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarFile;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.json.JacksonAcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
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
 * The SDK's core modules never depend on Quarkus (or on Vert.x, Mutiny or CDI, which
 * come with it): the framework integration depends on the core, never the other way.
 * Checked on the core jars this extension is built against: acp-annotations, acp-core,
 * acp-json-jackson2, acp-agent-support and acp-streamable-http-jetty.
 */
@AnalyzeClasses(locations = CoreIsFrameworkFreeArchitectureTest.CoreModules.class,
		importOptions = ImportOption.DoNotIncludeTests.class)
class CoreIsFrameworkFreeArchitectureTest {

	@ArchTest
	static void importsEveryCoreModule(JavaClasses classes) {
		CoreModules.ANCHORS.forEach(anchor -> assertThat(classes.contain(anchor)).as(anchor.getName()).isTrue());
	}

	@ArchTest
	static final ArchRule coreNeverDependsOnQuarkus = noClasses().should()
		.dependOnClassesThat()
		.resideInAnyPackage("io.quarkus..", "io.vertx..", "io.smallrye..", "jakarta.enterprise..", "jakarta.inject..")
		.because("the core modules serve every framework; framework integrations depend on them");

	/** Imports the jars (or class directories) of the core modules. */
	public static final class CoreModules implements LocationProvider {

		static final Set<Class<?>> ANCHORS = Set.of(AcpAgent.class, AcpSchema.class, JacksonAcpJsonMapper.class,
				AcpAgentSupport.class, StreamableHttpAcpServlet.class);

		@Override
		public Set<Location> get(Class<?> testClass) {
			Set<Location> locations = new LinkedHashSet<>();
			for (Class<?> anchor : ANCHORS) {
				Path root = root(anchor);
				try {
					locations.add(Files.isDirectory(root) ? Location.of(root) : Location.of(new JarFile(root.toFile())));
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}
			return locations;
		}

		private static Path root(Class<?> anchor) {
			try {
				return Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
			}
			catch (URISyntaxException ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

}
