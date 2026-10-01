/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.spec.AcpAgentSession;
import com.agentclientprotocol.sdk.spec.AcpClientSession;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.LocationProvider;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasModifiers.Predicates.modifier;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * acp-streamable-http-jetty: the agent side of the Streamable HTTP transport, as a servlet and an
 * embedded Jetty listener. It is an agent transport like those in acp-core: it builds on the
 * {@code spec} transport SPI and the schema, creates one agent per connection through the public
 * per-connection seam ({@code AcpAgentFactory}, {@code AcpAsyncAgent}, {@code RemoteAcpConnection}),
 * and never sees client code, the session implementations, other agent runtime classes, or
 * anything package-private in acp-core (it shares the {@code agent.transport} package with
 * acp-core, so the compiler would allow that). Within its package no two classes depend on each
 * other in a cycle. {@link ServletPortabilityArchitectureTest} keeps the servlet path free of Jetty.
 */
@AnalyzeClasses(locations = StreamableHttpJettyArchitectureTest.ThisModule.class,
		importOptions = ImportOption.DoNotIncludeTests.class)
class StreamableHttpJettyArchitectureTest {

	@ArchTest
	static void importsThisModuleAndNothingElse(JavaClasses classes) {
		assertThat(classes.contain(ThisModule.ANCHOR)).as("this module's classes are imported").isTrue();
		assertThat(classes).allMatch(ThisModule::isFromThisModule, "comes from this module's main classes");
	}

	/** Every package is its own slice, so a cycle between sub-packages is caught too. */
	@ArchTest
	static final ArchRule noPackageCyclesAtAnyDepth = slices().matching("com.agentclientprotocol.sdk.(**)")
		.should()
		.beFreeOfCycles();

	static final DescribedPredicate<JavaClass> AGENT_RUNTIME_BEYOND_THE_CONNECTION_SEAM = resideInAPackage(
			"com.agentclientprotocol.sdk.agent")
		.and(not(belongToAnyOf(AcpAgentFactory.class, AcpAsyncAgent.class)))
		.or(resideInAnyPackage("com.agentclientprotocol.sdk.client..", "com.agentclientprotocol.sdk.capabilities.."))
		.as("client or agent runtime classes other than AcpAgentFactory and AcpAsyncAgent");

	/**
	 * acp-core's internals as seen from another module: the session implementations (request
	 * correlation and dispatch, which the agent and client runtimes own) and anything not public.
	 */
	static final DescribedPredicate<JavaClass> SDK_INTERNALS = resideInAPackage("com.agentclientprotocol.sdk..")
		.and(not(ThisModule.classes()))
		.and(belongToAnyOf(AcpAgentSession.class, AcpClientSession.class).or(not(modifier(JavaModifier.PUBLIC))))
		.as("acp-core internals (session implementations, non-public classes)");

	@ArchTest
	static final ArchRule seesOnlyTheProtocolAndTheConnectionSeam = noClasses().should()
		.dependOnClassesThat(AGENT_RUNTIME_BEYOND_THE_CONNECTION_SEAM.or(SDK_INTERNALS))
		.because("a transport sits beneath the session and the runtime that use it");

	/** Nested classes count as their top-level class. */
	@ArchTest
	static final ArchRule noClassCycles = slices()
		.assignedFrom(topLevelClassesIn("com.agentclientprotocol.sdk.agent.transport"))
		.should()
		.beFreeOfCycles();

	static SliceAssignment topLevelClassesIn(String... packages) {
		List<String> names = Arrays.asList(packages);
		return new SliceAssignment() {

			@Override
			public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
				if (!names.contains(javaClass.getPackageName()) || !ThisModule.isFromThisModule(javaClass)) {
					return SliceIdentifier.ignore();
				}
				JavaClass topLevel = javaClass;
				while (topLevel.getEnclosingClass().isPresent()) {
					topLevel = topLevel.getEnclosingClass().get();
				}
				return SliceIdentifier.of(topLevel.getName());
			}

			@Override
			public String getDescription() {
				return "top-level classes of " + names;
			}

		};
	}

	/**
	 * Imports the directory or jar holding this module's main classes, found from {@code ANCHOR}, so
	 * the rules never see a dependency's classes or the tests.
	 */
	public static final class ThisModule implements LocationProvider {

		static final Class<?> ANCHOR = StreamableHttpAcpServlet.class;

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

		static boolean isFromThisModule(JavaClass javaClass) {
			String root = root().toUri().getPath();
			return javaClass.getBaseComponentType()
				.getSource()
				.map(source -> source.getUri().toString().contains(root))
				.orElse(false);
		}

		static DescribedPredicate<JavaClass> classes() {
			return DescribedPredicate.describe("classes of this module", ThisModule::isFromThisModule);
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
