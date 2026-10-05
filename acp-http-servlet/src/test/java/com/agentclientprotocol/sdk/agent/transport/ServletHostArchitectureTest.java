/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
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
 * The servlet is a host: it adapts I/O and depends only on the host contract
 * ({@code com.agentclientprotocol.sdk.http.server}) and the servlet and Jakarta WebSocket APIs.
 * It holds no protocol rule: no JSON-RPC message, routing, session or connection class.
 */
@AnalyzeClasses(locations = ServletHostArchitectureTest.ThisModule.class)
class ServletHostArchitectureTest {

	@ArchTest
	static final ArchRule dependsOnlyOnTheContract = classes().should()
		.onlyDependOnClassesThat(resideInAnyPackage("java..", "jakarta.servlet..", "jakarta.websocket..", "org.slf4j..",
				"reactor..", "org.reactivestreams..", "org.jspecify..", "com.agentclientprotocol.sdk.http.server..",
				"com.agentclientprotocol.sdk.agent.transport..")
			.or(belongToAnyOf(AcpAgentFactory.class, AcpJsonMapper.class, Assert.class, UnstableAcpApi.class))
			.as("the host contract, the servlet and Jakarta WebSocket APIs, and the SDK's entry types"))
		.because("all protocol semantics live in the endpoint; a host only adapts I/O");

	@ArchTest
	static final ArchRule holdsNoProtocolRules = noClasses().should()
		.dependOnClassesThat(resideInAnyPackage("com.agentclientprotocol.sdk.spec..")
			.or(belongToAnyOf(RemoteAcpConnection.class)))
		.because("JSON-RPC messages, routing and connections are the endpoint's");

	@ArchTest
	static final ArchRule isContainerNeutral = noClasses().should()
		.dependOnClassesThat(resideInAnyPackage("org.eclipse.jetty..", "org.apache.catalina..", "org.apache.tomcat..",
				"io.undertow.."))
		.because("the servlet must stay mountable in any Servlet 6 container");

	/** Imports this module's main classes only. */
	public static final class ThisModule implements LocationProvider {

		@Override
		public Set<Location> get(Class<?> testClass) {
			try {
				Path root = Path.of(StreamableHttpAcpServlet.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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
