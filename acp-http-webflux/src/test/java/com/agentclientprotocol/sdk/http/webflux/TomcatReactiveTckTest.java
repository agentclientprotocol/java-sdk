/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.webflux;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.agentclientprotocol.sdk.http.server.AcpHttpEndpoint;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.test.http.AcpHttpTransportTck;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;

import org.springframework.http.server.reactive.TomcatHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;

/**
 * The WebFlux host's route on Tomcat as a WebFlux server (Spring's Tomcat adapter, WebSocket
 * through Tomcat's Jakarta WebSocket), under the shared transport TCK.
 */
class TomcatReactiveTckTest extends AcpHttpTransportTck {

	@Override
	protected Host startHost(HostConfig config) throws Exception {
		AcpHttpEndpoint endpoint = AcpHttpEndpoint.create(AcpJsonMapper.createDefault(), config.agents(),
				config.options());
		endpoint.start();
		Path base = Files.createTempDirectory("acp-tomcat-reactive");
		Tomcat tomcat = new Tomcat();
		tomcat.setBaseDir(base.toString());
		Connector connector = new Connector();
		connector.setPort(0);
		connector.setProperty("address", "127.0.0.1");
		connector.setAsyncTimeout(config.containerAsyncTimeout().toMillis());
		tomcat.setConnector(connector);
		Context context = tomcat.addContext("", base.toString());
		context.addServletContainerInitializer(new WsSci(), null);
		var handler = RouterFunctions.toHttpHandler(new AcpWebFluxHost(endpoint).routerFunction("/acp"));
		Wrapper wrapper = Tomcat.addServlet(context, "webflux", new TomcatHttpHandlerAdapter(handler));
		wrapper.setAsyncSupported(true);
		wrapper.setLoadOnStartup(1);
		context.addServletMappingDecoded("/", "webflux");
		tomcat.start();
		URI uri = URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/acp");
		return new Host() {

			@Override
			public URI endpoint() {
				return uri;
			}

			@Override
			public void stop() throws Exception {
				endpoint.closeGracefully().block(Duration.ofSeconds(10));
				tomcat.stop();
				tomcat.destroy();
			}

		};
	}

}
