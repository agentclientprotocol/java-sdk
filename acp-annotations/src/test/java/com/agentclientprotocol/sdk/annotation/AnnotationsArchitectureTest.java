/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

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
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.LocationProvider;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * acp-annotations: the annotations an agent class is written with. Agent code compiles against
 * them without pulling in the SDK, so the module depends on nothing in it, nor on anything but
 * the JDK and JSpecify's nullness annotations.
 */
@AnalyzeClasses(locations = AnnotationsArchitectureTest.ThisModule.class,
		importOptions = ImportOption.DoNotIncludeTests.class)
class AnnotationsArchitectureTest {

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
	static final ArchRule dependsOnNothingInTheSdk = classes().should()
		.onlyDependOnClassesThat()
		.resideInAnyPackage("com.agentclientprotocol.sdk.annotation..", "java..", "org.jspecify..")
		.because("annotations are a compile-time vocabulary; acp-agent-support interprets them");

	/**
	 * Imports the directory or jar holding this module's main classes, found from {@code ANCHOR}, so
	 * the rules never see a dependency's classes or the tests.
	 */
	public static final class ThisModule implements LocationProvider {

		static final Class<?> ANCHOR = AcpAgent.class;

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
