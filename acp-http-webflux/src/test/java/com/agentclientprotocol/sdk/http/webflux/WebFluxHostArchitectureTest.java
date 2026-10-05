/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.webflux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;

import com.agentclientprotocol.sdk.agent.transport.RemoteAcpConnection;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.util.Assert;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.LocationProvider;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The WebFlux host is a host: it adapts I/O and depends only on the host contract
 * ({@code com.agentclientprotocol.sdk.http.server}) and Spring WebFlux. It holds no protocol
 * rule: no JSON-RPC message, routing, session or connection class. Reactor Netty is touched in
 * one place, which loads only when Netty is the server.
 */
@AnalyzeClasses(locations = WebFluxHostArchitectureTest.ThisModule.class)
class WebFluxHostArchitectureTest {

	@ArchTest
	static final ArchRule dependsOnlyOnTheContract = classes().should()
		.onlyDependOnClassesThat(resideInAnyPackage("java..", "org.springframework.web..", "org.springframework.http..",
				"org.springframework.core.io.buffer..", "reactor.core..", "reactor.netty..", "io.netty..",
				"org.reactivestreams..", "org.slf4j..", "org.jspecify..", "com.agentclientprotocol.sdk.http.server..",
				"com.agentclientprotocol.sdk.http.webflux..")
			.or(belongToAnyOf(StreamableHttpAcpAgentTransportOptions.class, Assert.class, UnstableAcpApi.class))
			.as("the host contract, Spring WebFlux, and the SDK's entry types"))
		.because("all protocol semantics live in the endpoint; a host only adapts I/O");

	@ArchTest
	static final ArchRule holdsNoProtocolRules = noClasses().should()
		.dependOnClassesThat(resideInAnyPackage("com.agentclientprotocol.sdk.spec..", "com.agentclientprotocol.sdk.json..")
			.or(belongToAnyOf(RemoteAcpConnection.class)))
		.because("JSON-RPC messages, routing and connections are the endpoint's");

	@ArchTest
	static final ArchRule touchesNettyInOnePlace = noClasses().that()
		.doNotHaveFullyQualifiedName(WebSocketUpgrades.class.getName() + "$Netty")
		.should()
		.dependOnClassesThat(resideInAnyPackage("reactor.netty..", "io.netty.."))
		.because("Reactor Netty is one of WebFlux's servers, loaded only when it is the one serving");

	@ArchTest
	static final ArchRule isServerNeutral = noClasses().should()
		.dependOnClassesThat(resideInAnyPackage("org.eclipse.jetty..", "org.apache.catalina..", "org.apache.tomcat..",
				"io.undertow..", "jakarta.servlet.."))
		.because("the route must stay mountable on any WebFlux server");

	/** Imports this module's main classes only. */
	public static final class ThisModule implements LocationProvider {

		@Override
		public Set<Location> get(Class<?> testClass) {
			try {
				Path root = Path.of(AcpWebFluxHost.class.getProtectionDomain().getCodeSource().getLocation().toURI());
				return Set.of(Files.isDirectory(root) ? Location.of(root) : Location.of(new JarFile(root.toFile())));
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			catch (URISyntaxException ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

}
