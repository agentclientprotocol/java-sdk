/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The core is framework-neutral: it depends on no server, servlet, WebSocket or framework API,
 * so every host (servlet, Vert.x, WebFlux) can mount it; and the host contract is public while
 * the protocol implementation is not.
 */
@AnalyzeClasses(packages = { "com.agentclientprotocol.sdk.http.server", "com.agentclientprotocol.sdk.agent.transport" },
		importOptions = ImportOption.DoNotIncludeTests.class)
class HttpCoreArchitectureTest {

	@ArchTest
	static final ArchRule isServerNeutral = noClasses().that()
		.resideInAPackage("com.agentclientprotocol.sdk.http.server..")
		.should()
		.dependOnClassesThat(resideInAnyPackage("jakarta..", "javax.servlet..", "org.eclipse.jetty..", "org.apache..",
				"io.undertow..", "io.vertx..", "io.netty..", "org.springframework..", "io.quarkus..", "io.micronaut.."))
		.because("all protocol semantics live in one framework-neutral core; hosts adapt I/O");

	@ArchTest
	static final ArchRule protocolClassesAreNotPublic = classes().that()
		.resideInAPackage("com.agentclientprotocol.sdk.http.server")
		.and()
		.haveSimpleNameStartingWith("StreamableHttp")
		.or()
		.haveSimpleNameContaining("SseOutboundStream")
		.or()
		.haveSimpleNameContaining("WebSocketConnection")
		.or()
		.haveSimpleNameStartingWith("Default")
		.should()
		.notBePublic()
		.because("a host depends only on the contract: routing, sessions, mailboxes and connections are the core's");

}
