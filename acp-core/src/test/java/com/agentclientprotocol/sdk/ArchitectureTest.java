/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

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
 */
@AnalyzeClasses(packages = "com.agentclientprotocol.sdk", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

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

	@ArchTest
	static final ArchRule coreUsesOnlyJacksonAnnotations = noClasses().that()
		.resideInAPackage("com.agentclientprotocol.sdk..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.fasterxml.jackson.databind..", "com.fasterxml.jackson.core..", "tools.jackson..")
		.because("acp-core carries only Jackson annotations; a JSON implementation lives in acp-json-jackson2 "
				+ "or acp-json-jackson3 behind AcpJsonMapper (#12)");

}
