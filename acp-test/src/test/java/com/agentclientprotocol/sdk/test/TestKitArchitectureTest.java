/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;

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
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasModifiers.Predicates.modifier;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * acp-test: an in-memory transport pair and a mock agent and client for application tests. It
 * stands where an application stands, so it may use anything public in acp-core, including the
 * sessions, but nothing package-private; and it sits beneath the annotation layer, whose tests
 * use it, so it never depends on acp-agent-support or acp-annotations.
 */
@AnalyzeClasses(locations = TestKitArchitectureTest.ThisModule.class,
		importOptions = ImportOption.DoNotIncludeTests.class)
class TestKitArchitectureTest {

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
	static final ArchRule usesOnlyPublicSdkTypes = noClasses().should()
		.dependOnClassesThat(resideInAPackage("com.agentclientprotocol.sdk..")
			.and(not(ThisModule.classes()))
			.and(not(modifier(JavaModifier.PUBLIC)))
			.as("non-public SDK classes"))
		.because("the test kit is written against the API an application sees");

	@ArchTest
	static final ArchRule staysBeneathTheAnnotationLayer = noClasses().should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.agentclientprotocol.sdk.agent.support..", "com.agentclientprotocol.sdk.annotation..")
		.because("acp-agent-support's tests use the test kit");

	/**
	 * Imports the directory or jar holding this module's main classes, found from {@code ANCHOR}, so
	 * the rules never see a dependency's classes or the tests.
	 */
	public static final class ThisModule implements LocationProvider {

		static final Class<?> ANCHOR = InMemoryTransportPair.class;

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
