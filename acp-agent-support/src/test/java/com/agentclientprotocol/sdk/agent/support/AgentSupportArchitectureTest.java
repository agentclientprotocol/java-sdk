/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.AcpJsonMapperSupplier;
import com.agentclientprotocol.sdk.json.TypeRef;
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

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasModifiers.Predicates.modifier;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * acp-agent-support: annotation-driven agents.
 *
 * <pre>
 *            support (AcpAgentSupport, AcpHandlerMethod)
 *              /             |              \
 *       resolver          handler         interceptor
 *              \             |              /
 *                         invocation
 * </pre>
 *
 * <p>
 * {@code AcpAgentSupport} discovers handler methods and drives them through three independent
 * extension points, which see only the invocation model, never the entry classes or each other.
 * The module builds on acp-core's public agent API and runs over any {@code AcpAgentTransport}:
 * it does not reach into session implementations, client code, concrete transports or a JSON
 * implementation.
 * </p>
 */
@AnalyzeClasses(locations = AgentSupportArchitectureTest.ThisModule.class,
		importOptions = ImportOption.DoNotIncludeTests.class)
class AgentSupportArchitectureTest {

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

	@ArchTest
	static final ArchRule layers = layeredArchitecture().consideringOnlyDependenciesInLayers()
		.layer("Support").definedBy("com.agentclientprotocol.sdk.agent.support")
		.layer("Resolver").definedBy("com.agentclientprotocol.sdk.agent.support.resolver..")
		.layer("Handler").definedBy("com.agentclientprotocol.sdk.agent.support.handler..")
		.layer("Interceptor").definedBy("com.agentclientprotocol.sdk.agent.support.interceptor..")
		.layer("Invocation").definedBy("com.agentclientprotocol.sdk.agent.support.invocation..")
		.whereLayer("Support").mayNotBeAccessedByAnyLayer()
		.whereLayer("Resolver").mayOnlyBeAccessedByLayers("Support")
		.whereLayer("Handler").mayOnlyBeAccessedByLayers("Support")
		.whereLayer("Interceptor").mayOnlyBeAccessedByLayers("Support")
		.whereLayer("Invocation").mayOnlyBeAccessedByLayers("Support", "Resolver", "Handler", "Interceptor")
		.because("the extension points are written against the invocation model, not against AcpAgentSupport");

	/**
	 * acp-core's internals as seen from another module: the session implementations (request
	 * correlation and dispatch, which the agent and client runtimes own) and anything not public.
	 */
	static final DescribedPredicate<JavaClass> SDK_INTERNALS = resideInAPackage("com.agentclientprotocol.sdk..")
		.and(not(ThisModule.classes()))
		.and(belongToAnyOf(AcpAgentSession.class, AcpClientSession.class).or(not(modifier(JavaModifier.PUBLIC))))
		.as("acp-core internals (session implementations, non-public classes)");

	@ArchTest
	static final ArchRule usesOnlyThePublicAgentApi = noClasses().should()
		.dependOnClassesThat(SDK_INTERNALS)
		.because("annotation support is a client of acp-core's agent API, like any hand-written agent");

	@ArchTest
	static final ArchRule isTransportClientAndJsonAgnostic = noClasses().should()
		.dependOnClassesThat(resideInAnyPackage("com.agentclientprotocol.sdk.client..",
				"com.agentclientprotocol.sdk.agent.transport..")
			.or(resideInAPackage("com.agentclientprotocol.sdk.json..")
				.and(not(belongToAnyOf(AcpJsonMapper.class, AcpJsonMapperSupplier.class, TypeRef.class))))
			.as("client code, concrete transports or a JSON implementation"))
		.because("an annotated agent runs over whatever AcpAgentTransport and JSON mapper it is given");

	/**
	 * Imports the directory or jar holding this module's main classes, found from {@code ANCHOR}, so
	 * the rules never see a dependency's classes or the tests.
	 */
	public static final class ThisModule implements LocationProvider {

		static final Class<?> ANCHOR = AcpAgentSupport.class;

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
