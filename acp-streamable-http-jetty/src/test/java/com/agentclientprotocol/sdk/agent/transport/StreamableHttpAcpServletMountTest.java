/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The servlet mounted in an application's own container, at the application's own path,
 * the way a Spring Boot or Tomcat app would register it; no StreamableHttpAcpAgentTransport.
 */
class StreamableHttpAcpServletMountTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Test
	void servletMountedInAnApplicationsOwnContainerServesAcp() throws Exception {
		AcpAgentFactory factory = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
			.initializeHandler(r -> Mono.just(new AcpSchema.InitializeResponse(AcpSchema.LATEST_PROTOCOL_VERSION,
					new AcpSchema.AgentCapabilities(), List.of())))
			.newSessionHandler(r -> Mono.just(new AcpSchema.NewSessionResponse("mounted-1", null, null)))
			.promptHandler((r, ctx) -> ctx.sendMessage("hello from a mounted servlet")
				.thenReturn(AcpSchema.PromptResponse.endTurn()))
			.build());
		StreamableHttpAcpServlet servlet = new StreamableHttpAcpServlet(AcpJsonMapper.createDefault(), factory);

		// An application's server: its own context path, its own servlet mapping.
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server);
		connector.setPort(0);
		server.addConnector(connector);
		ServletContextHandler context = new ServletContextHandler("/app");
		ServletHolder holder = new ServletHolder(servlet);
		holder.setAsyncSupported(true);
		context.addServlet(holder, "/agents/acp");
		server.setHandler(context);
		server.start();

		URI endpoint = URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/app/agents/acp");
		AcpAsyncClient client = AcpClient
			.async(new StreamableHttpAcpClientTransport(endpoint, AcpJsonMapper.createDefault()))
			.requestTimeout(TIMEOUT)
			.build();
		try {
			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse session = client
				.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
				.block(TIMEOUT);
			AcpSchema.PromptResponse prompt = client
				.prompt(new AcpSchema.PromptRequest(session.sessionId(), List.of(new AcpSchema.TextContent("hi"))))
				.block(TIMEOUT);
			assertThat(prompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			assertThat(servlet.activeConnectionCount()).isEqualTo(1);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			// The container's shutdown calls destroy(), which closes every connection.
			server.stop();
		}
		assertThat(servlet.activeConnectionCount()).isZero();
	}

}
