/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;

import com.agentclientprotocol.sdk.quarkus.runtime.AcpAgentAssembly;
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
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * acp-quarkus: the extension's runtime half.
 *
 * <pre>
 *   quarkus (configuration)  &lt;--  quarkus.runtime (beans, hosts)  --&gt;  acp-integration
 * </pre>
 *
 * <p>
 * The public package is what an application touches and knows nothing of the beans
 * behind it. The beans use the SDK's public API only, like any application, and never
 * Jetty or a servlet container: the HTTP endpoint is the SDK's framework-neutral endpoint on a
 * Vert.x route of the Quarkus router. The route is a host: it holds no protocol rule.
 * </p>
 */
@AnalyzeClasses(locations = QuarkusArchitectureTest.ThisModule.class,
		importOptions = ImportOption.DoNotIncludeTests.class)
class QuarkusArchitectureTest {

	@ArchTest
	static void importsThisModuleAndNothingElse(JavaClasses classes) {
		assertThat(classes.contain(ThisModule.ANCHOR)).as("this module's classes are imported").isTrue();
		assertThat(classes).allMatch(ThisModule::isFromThisModule, "comes from this module's main classes");
	}

	@ArchTest
	static final ArchRule noPackageCyclesAtAnyDepth = slices().matching("com.agentclientprotocol.sdk.(**)")
		.should()
		.beFreeOfCycles();

	@ArchTest
	static final ArchRule publicPackageDoesNotReachTheBeans = noClasses().that()
		.resideInAPackage("com.agentclientprotocol.sdk.quarkus")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.agentclientprotocol.sdk.quarkus.runtime..")
		.because("the configuration and contracts an application uses do not depend on the extension's beans");

	static final DescribedPredicate<JavaClass> SDK_INTERNALS = resideInAPackage("com.agentclientprotocol.sdk..")
		.and(not(ThisModule.classes()))
		.and(belongToAnyOf(AcpAgentSession.class, AcpClientSession.class).or(not(modifier(JavaModifier.PUBLIC))))
		.as("SDK internals (session implementations, non-public classes)");

	@ArchTest
	static final ArchRule usesOnlyThePublicSdkApi = noClasses().should()
		.dependOnClassesThat(SDK_INTERNALS)
		.because("the extension is a client of the SDK's public API, like any application");

	@ArchTest
	static final ArchRule theRouteIsOnlyAHost = noClasses().should()
		.dependOnClassesThat(resideInAnyPackage("jakarta.servlet..", "jakarta.websocket..", "io.undertow..")
			.or(belongToAnyOf(com.agentclientprotocol.sdk.agent.transport.RemoteAcpConnection.class,
					com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage.class)))
		.because("all protocol semantics live in acp-http-core; the Vert.x route only adapts I/O through the host contract");

	@ArchTest
	static final ArchRule neverUsesJetty = noClasses().should()
		.dependOnClassesThat()
		.resideInAPackage("org.eclipse.jetty..")
		.because("Quarkus serves the agent on its own HTTP server, and acp-quarkus excludes the Jetty jars");

	/** Imports the directory or jar holding this module's main classes, found from {@code ANCHOR}. */
	public static final class ThisModule implements LocationProvider {

		static final Class<?> ANCHOR = AcpAgentAssembly.class;

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
