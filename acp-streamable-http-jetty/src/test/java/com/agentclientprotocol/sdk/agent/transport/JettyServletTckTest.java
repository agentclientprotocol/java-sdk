/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.net.URI;
import java.time.Duration;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.test.http.AcpHttpTransportTck;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * The servlet host on Jetty 12.1 (EE 10), with Jetty's Jakarta WebSocket implementation, as an
 * application's own Jetty runs it.
 */
class JettyServletTckTest extends AcpHttpTransportTck {

	@Override
	protected Host startHost(HostConfig config) throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server);
		connector.setHost("127.0.0.1");
		connector.setPort(0);
		server.addConnector(connector);
		ServletContextHandler context = new ServletContextHandler("/");
		StreamableHttpAcpServlet servlet = new StreamableHttpAcpServlet(AcpJsonMapper.createDefault(), config.agents(),
				config.options());
		ServletHolder holder = new ServletHolder(servlet);
		holder.setAsyncSupported(true);
		holder.setInitOrder(1);
		context.addServlet(holder, "/acp");
		JakartaWebSocketServletContainerInitializer.configure(context, null);
		server.setHandler(context);
		server.start();
		URI endpoint = URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/acp");
		return new Host() {

			@Override
			public URI endpoint() {
				return endpoint;
			}

			@Override
			public void stop() throws Exception {
				servlet.closeGracefully().block(Duration.ofSeconds(10));
				server.stop();
			}

		};
	}

}
