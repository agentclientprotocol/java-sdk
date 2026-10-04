/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.test.http.AcpHttpTransportTck;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;

/**
 * The servlet host on embedded Tomcat 11, with Tomcat's Jakarta WebSocket implementation: the
 * container that allows one outstanding WebSocket write, and whose connector times async
 * requests out.
 */
class TomcatServletTckTest extends AcpHttpTransportTck {

	@Override
	protected Host startHost(HostConfig config) throws Exception {
		Path base = Files.createTempDirectory("acp-tomcat");
		Tomcat tomcat = new Tomcat();
		tomcat.setBaseDir(base.toString());
		Connector connector = new Connector();
		connector.setPort(0);
		connector.setProperty("address", "127.0.0.1");
		connector.setAsyncTimeout(config.containerAsyncTimeout().toMillis());
		tomcat.setConnector(connector);
		Context context = tomcat.addContext("", base.toString());
		context.addServletContainerInitializer(new WsSci(), null);
		StreamableHttpAcpServlet servlet = new StreamableHttpAcpServlet(AcpJsonMapper.createDefault(), config.agents(),
				config.options());
		Wrapper wrapper = Tomcat.addServlet(context, "acp", servlet);
		wrapper.setAsyncSupported(true);
		wrapper.setLoadOnStartup(1);
		context.addServletMappingDecoded("/acp", "acp");
		tomcat.start();
		URI endpoint = URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/acp");
		return new Host() {

			@Override
			public URI endpoint() {
				return endpoint;
			}

			@Override
			public void stop() throws Exception {
				// As a framework does: drain the endpoint, then stop the container.
				servlet.closeGracefully().block(java.time.Duration.ofSeconds(10));
				tomcat.stop();
				tomcat.destroy();
			}

		};
	}

}
