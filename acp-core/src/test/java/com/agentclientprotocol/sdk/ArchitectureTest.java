/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk;

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
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
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
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package structure of acp-core, as it is and as it must stay.
 *
 * <pre>
 *   client, client.transport     agent, agent.transport
 *                  \                /
 *                   capabilities
 *                        |
 *            protocol: spec + error
 *                   /          \
 *                json          util
 * </pre>
 *
 * <p>
 * {@code spec} and {@code error} form one layer, the protocol vocabulary, but dependencies
 * between them run one way: {@code spec} uses {@code error} (sessions raise protocol
 * exceptions; {@code JSONRPCError} converts to and from them), never the reverse.
 * </p>
 *
 * <p>
 * The rules see acp-core's main classes only: {@link ThisModule} imports the directory or
 * jar that holds them, so a module that scans this test (the JSON modules do) still checks
 * acp-core and not itself.
 * </p>
 */
@AnalyzeClasses(locations = ArchitectureTest.ThisModule.class,
		importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {


	@ArchTest
	static final ArchRule isFrameworkNeutral = noClasses().should()
		.dependOnClassesThat(resideInAnyPackage("org.springframework..", "io.quarkus..", "io.micronaut..",
				"com.agentclientprotocol.sdk.spring..", "com.agentclientprotocol.sdk.micronaut..",
				"com.agentclientprotocol.sdk.integration..")
			.as("a framework or a framework integration"))
		.because("framework integrations build on the SDK, never the reverse");
	@ArchTest
	static void importsAcpCoreAndNothingElse(JavaClasses classes) {
		assertThat(classes.contain(AcpSchema.class)).as("acp-core's classes are imported").isTrue();
		assertThat(classes).allMatch(ThisModule::isFromThisModule, "comes from acp-core's main classes");
	}

	@ArchTest
	static final ArchRule layers = layeredArchitecture().consideringOnlyDependenciesInLayers()
		.layer("Util").definedBy("com.agentclientprotocol.sdk.util..")
		.layer("Json").definedBy("com.agentclientprotocol.sdk.json..")
		.layer("Protocol").definedBy("com.agentclientprotocol.sdk.spec..", "com.agentclientprotocol.sdk.error..")
		.layer("Capabilities").definedBy("com.agentclientprotocol.sdk.capabilities..")
		.layer("Client").definedBy("com.agentclientprotocol.sdk.client..")
		.layer("Agent").definedBy("com.agentclientprotocol.sdk.agent..")
		.whereLayer("Client").mayNotBeAccessedByAnyLayer()
		.whereLayer("Agent").mayNotBeAccessedByAnyLayer()
		.whereLayer("Capabilities").mayOnlyBeAccessedByLayers("Client", "Agent")
		.whereLayer("Protocol").mayOnlyBeAccessedByLayers("Capabilities", "Client", "Agent")
		.whereLayer("Json").mayOnlyBeAccessedByLayers("Protocol", "Capabilities", "Client", "Agent")
		.because("clients and agents share the protocol layer but never each other; the JSON SPI and "
				+ "utilities sit underneath everything");

	@ArchTest
	static final ArchRule noPackageCycles = slices().matching("com.agentclientprotocol.sdk.(*)..")
		.should()
		.beFreeOfCycles();

	/** Every package is its own slice, so a cycle between sub-packages is caught too. */
	@ArchTest
	static final ArchRule noPackageCyclesAtAnyDepth = slices().matching("com.agentclientprotocol.sdk.(**)")
		.should()
		.beFreeOfCycles();

	@ArchTest
	static final ArchRule coreUsesOnlyJacksonAnnotations = noClasses().that()
		.resideInAPackage("com.agentclientprotocol.sdk..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.fasterxml.jackson.databind..", "com.fasterxml.jackson.core..", "tools.jackson..")
		.because("acp-core carries only Jackson annotations; a JSON implementation lives in acp-json-jackson2 "
				+ "or acp-json-jackson3 behind AcpJsonMapper (#12)");

	/** The session implementations: request correlation, handler dispatch, timeouts. */
	static final DescribedPredicate<JavaClass> SESSION_IMPLEMENTATIONS = belongToAnyOf(AcpClientSession.class,
			AcpAgentSession.class)
		.as("the session implementations");

	/**
	 * A client transport moves JSON-RPC messages for an {@code AcpClientSession}; it is built
	 * from the {@code spec} transport interface and the schema, and knows nothing of the client
	 * or agent runtime, nor of the session that drives it.
	 */
	@ArchTest
	static final ArchRule clientTransportsSeeOnlyTheProtocol = noClasses().that()
		.resideInAPackage("com.agentclientprotocol.sdk.client.transport..")
		.should()
		.dependOnClassesThat(resideInAnyPackage("com.agentclientprotocol.sdk.client",
				"com.agentclientprotocol.sdk.agent..", "com.agentclientprotocol.sdk.capabilities..")
			.as("client or agent runtime classes")
			.or(SESSION_IMPLEMENTATIONS))
		.because("a transport sits beneath the session and the runtime that use it");

	static final DescribedPredicate<JavaClass> AGENT_RUNTIME_BEYOND_THE_CONNECTION_SEAM = resideInAPackage(
			"com.agentclientprotocol.sdk.agent")
		.and(not(belongToAnyOf(AcpAgentFactory.class, AcpAsyncAgent.class)))
		.or(resideInAnyPackage("com.agentclientprotocol.sdk.client..", "com.agentclientprotocol.sdk.capabilities.."))
		.as("client or agent runtime classes other than AcpAgentFactory and AcpAsyncAgent");

	/**
	 * An agent transport moves JSON-RPC messages for an {@code AcpAgentSession}. A listener-backed
	 * transport also creates one agent per accepted connection, so it may use the public
	 * per-connection seam ({@link AcpAgentFactory} and the {@link AcpAsyncAgent} it returns),
	 * and nothing else of the agent runtime: not the {@code Default*} implementations, not the
	 * sessions, not the client.
	 */
	@ArchTest
	static final ArchRule agentTransportsSeeOnlyTheProtocolAndTheConnectionSeam = noClasses().that()
		.resideInAPackage("com.agentclientprotocol.sdk.agent.transport..")
		.should()
		.dependOnClassesThat(AGENT_RUNTIME_BEYOND_THE_CONNECTION_SEAM.or(SESSION_IMPLEMENTATIONS))
		.because("a transport sits beneath the session and the runtime that use it");

	/**
	 * Inside each package no two top-level classes depend on each other, directly or through
	 * others (nested classes count as their top-level class). The one package left out is
	 * {@code json}: {@code AcpJsonMapper.createDefault()} finds the default mapper through
	 * {@code AcpJsonMapperSelector} and the {@code AcpJsonMapperSupplier} SPI, which supplies
	 * {@code AcpJsonMapper}s, a cycle that is the public default-lookup API itself.
	 */
	@ArchTest
	static final ArchRule noClassCycles = slices()
		.assignedFrom(topLevelClassesIn("com.agentclientprotocol.sdk.spec", "com.agentclientprotocol.sdk.error",
				"com.agentclientprotocol.sdk.util", "com.agentclientprotocol.sdk.capabilities",
				"com.agentclientprotocol.sdk.client", "com.agentclientprotocol.sdk.client.transport",
				"com.agentclientprotocol.sdk.agent", "com.agentclientprotocol.sdk.agent.transport"))
		.should()
		.beFreeOfCycles();

	static SliceAssignment topLevelClassesIn(String... packages) {
		List<String> names = Arrays.asList(packages);
		return new SliceAssignment() {

			@Override
			public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
				if (!names.contains(javaClass.getPackageName())) {
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

	/** Imports the directory or jar holding acp-core's main classes. */
	public static final class ThisModule implements LocationProvider {

		static final Class<?> ANCHOR = AcpSchema.class;

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
