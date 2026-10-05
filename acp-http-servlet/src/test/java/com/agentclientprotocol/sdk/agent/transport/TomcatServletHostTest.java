/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the transport TCK does not reach on Tomcat: a reply the endpoint is not ready with at once
 * (an {@code initialize} waiting for a slow agent) is written asynchronously, and a container
 * without Jakarta WebSocket answers an upgrade 501. Tomcat here has no WebSocket initializer.
 */
class TomcatServletHostTest {

	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}""";

	private final AcpAgentFactory slowAgents = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
		.initializeHandler(request -> Mono.delay(Duration.ofMillis(200)).thenReturn(AcpSchema.InitializeResponse.ok()))
		.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn()))
		.build());

	private final List<Throwable> errors = new CopyOnWriteArrayList<>();

	private Tomcat tomcat;

	private StreamableHttpAcpServlet servlet;

	private int port;

	@BeforeEach
	void start() throws Exception {
		Path base = Files.createTempDirectory("acp-tomcat");
		tomcat = new Tomcat();
		tomcat.setBaseDir(base.toString());
		Connector connector = new Connector();
		connector.setPort(0);
		connector.setProperty("address", "127.0.0.1");
		tomcat.setConnector(connector);
		Context context = tomcat.addContext("", base.toString());
		servlet = new StreamableHttpAcpServlet(slowAgents);
		servlet.setExceptionHandler(errors::add);
		Wrapper wrapper = Tomcat.addServlet(context, "acp", servlet);
		wrapper.setAsyncSupported(true);
		wrapper.setLoadOnStartup(1);
		context.addServletMappingDecoded("/acp", "acp");
		tomcat.start();
		port = connector.getLocalPort();
	}

	@AfterEach
	void stop() throws Exception {
		servlet.closeGracefully().block(Duration.ofSeconds(10));
		tomcat.stop();
		tomcat.destroy();
	}

	@Test
	void aReplyTheAgentIsSlowWithIsWrittenAsynchronously() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/acp"))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))
				.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("%s %s %s", response.body(), response.headers().map(), errors).isEqualTo(200);
		assertThat(response.body()).contains("\"result\"");
		assertThat(response.headers().firstValue("Acp-Connection-Id")).isPresent();
		assertThat(servlet.activeConnectionCount()).isEqualTo(1);
		assertThat(errors).isEmpty();
	}

	@Test
	void anUpgradeWithoutJakartaWebSocketIsNotImplemented() throws Exception {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(10_000);
			OutputStream out = socket.getOutputStream();
			out.write(("GET /acp HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nUpgrade: websocket\r\n"
					+ "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
					+ "Sec-WebSocket-Version: 13\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
			out.flush();
			String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
				.readLine();
			assertThat(status).startsWith("HTTP/1.1 501");
		}
	}

}
