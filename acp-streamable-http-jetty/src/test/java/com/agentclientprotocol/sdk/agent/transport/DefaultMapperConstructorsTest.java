/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP and WebSocket transports, client and agent side, can be created without a JSON mapper
 * argument: they use the default mapper, as the stdio transports do.
 */
class DefaultMapperConstructorsTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static AcpAgentFactory factory() {
		return AcpAgentFactory.async(transport -> AcpAgent.async(transport)
			.initializeHandler(r -> Mono.just(AcpSchema.InitializeResponse.ok()))
			.promptHandler((r, context) -> Mono.just(AcpSchema.PromptResponse.endTurn()))
			.build());
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static void initialize(AcpSyncClient client) {
		try {
			assertThat(client.initialize().protocolVersion()).isEqualTo(AcpSchema.LATEST_PROTOCOL_VERSION);
		}
		finally {
			client.close();
		}
	}

	@Test
	void theListenerAndBothClientTransportsUseTheDefaultMapper() throws Exception {
		int port = freePort();
		StreamableHttpAcpAgentTransport listener = new StreamableHttpAcpAgentTransport(port, factory());
		listener.start().block(TIMEOUT);
		try {
			initialize(AcpClient
				.sync(new StreamableHttpAcpClientTransport(URI.create("http://127.0.0.1:" + port + "/acp")))
				.requestTimeout(TIMEOUT)
				.build());
			initialize(AcpClient
				.sync(new WebSocketAcpClientTransport(URI.create("ws://127.0.0.1:" + port + "/acp")))
				.requestTimeout(TIMEOUT)
				.build());
		}
		finally {
			listener.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void theServletUsesTheDefaultMapper() throws Exception {
		StreamableHttpAcpServlet servlet = new StreamableHttpAcpServlet(factory());
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server);
		connector.setPort(0);
		server.addConnector(connector);
		ServletContextHandler context = new ServletContextHandler("/");
		ServletHolder holder = new ServletHolder(servlet);
		holder.setAsyncSupported(true);
		context.addServlet(holder, "/acp");
		server.setHandler(context);
		server.start();
		try {
			initialize(AcpClient
				.sync(new StreamableHttpAcpClientTransport(
						URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/acp")))
				.requestTimeout(TIMEOUT)
				.build());
		}
		finally {
			servlet.closeGracefully().block(TIMEOUT);
			server.stop();
		}
	}

}
