/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

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

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * acp-json-jackson2: an {@code AcpJsonMapper} implementation, found through
 * {@code AcpJsonMapperSupplier}. It implements acp-core's JSON SPI and may see the schema it maps,
 * nothing else of the SDK: no client, agent, session, transport or capability code, no
 * package-private acp-core class (it shares the {@code json} package with acp-core, so the compiler would
 * allow it), and not the other JSON implementation.
 */
@AnalyzeClasses(locations = Jackson2ArchitectureTest.ThisModule.class,
		importOptions = ImportOption.DoNotIncludeTests.class)
class Jackson2ArchitectureTest {

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

	/** acp-core's JSON SPI: what an implementation implements and receives. */
	static final DescribedPredicate<JavaClass> JSON_SPI = belongToAnyOf(AcpJsonMapper.class,
			AcpJsonMapperSupplier.class, TypeRef.class)
		.as("the JSON SPI (AcpJsonMapper, AcpJsonMapperSupplier, TypeRef)");

	@ArchTest
	static final ArchRule usesOnlyTheJsonSpiAndTheSchema = noClasses().should()
		.dependOnClassesThat(resideInAPackage("com.agentclientprotocol.sdk..")
			.and(not(ThisModule.classes()))
			.and(not(JSON_SPI))
			.and(not(resideInAPackage("com.agentclientprotocol.sdk.spec..")))
			.as("SDK classes other than the JSON SPI and the schema"))
		.because("a JSON implementation plugs in beneath the protocol layer and must not know what uses it");

	/**
	 * Imports the directory or jar holding this module's main classes, found from {@code ANCHOR}, so
	 * the rules never see a dependency's classes or the tests.
	 */
	public static final class ThisModule implements LocationProvider {

		static final Class<?> ANCHOR = JacksonAcpJsonMapper.class;

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
