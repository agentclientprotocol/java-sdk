/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * {@link StreamableHttpAcpServlet} is documented as mountable in any Servlet 6 container
 * (Spring Boot, Tomcat, Undertow, Jetty). That holds only while it and everything it uses
 * stay on the Servlet API. Jetty types belong to the embedded listener
 * ({@link StreamableHttpAcpAgentTransport}) and the WebSocket upgrade path only.
 */
@AnalyzeClasses(packages = "com.agentclientprotocol.sdk.agent.transport",
		importOptions = ImportOption.DoNotIncludeTests.class)
class ServletPortabilityArchitectureTest {

	@ArchTest
	static final ArchRule servletPathIsContainerNeutral = noClasses().that()
		.haveSimpleNameStartingWith("StreamableHttpAcpServlet")
		.or().haveSimpleNameStartingWith("StreamableHttpConnection")
		.or().haveSimpleNameStartingWith("SseOutboundStream")
		.or().haveSimpleNameStartingWith("StreamableHttpRouting")
		.or().haveSimpleNameStartingWith("StreamableHttpAcpAgentTransportOptions")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("org.eclipse.jetty..")
		.because("the servlet must stay mountable in any Servlet 6 container");

}
