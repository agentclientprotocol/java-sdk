/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.test.http.AcpHttpTransportTck;
import com.agentclientprotocol.sdk.test.http.HttpProbes;
import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WebFlux host routed by the autoconfiguration in a reactive application on its own
 * Reactor Netty server ({@code server.port}), under the shared transport TCK: HTTP, SSE and
 * WebSocket on one path of one server, beside the application's own routes, and drained before
 * Boot's graceful shutdown.
 */
class SpringWebFluxTckTests extends AcpHttpTransportTck {

	@Override
	protected Host startHost(HostConfig config) {
		var options = config.options();
		ConfigurableApplicationContext context = new SpringApplicationBuilder(TckApplication.class)
			.initializers(ctx -> ((GenericApplicationContext) ctx).registerBean("tckAgentFactory", AcpAgentFactory.class,
					config::agents))
			.run("--spring.main.web-application-type=reactive", "--server.port=0", "--server.address=127.0.0.1",
					"--spring.acp.agent.transport.type=http",
					"--spring.acp.agent.transport.http.keep-alive-interval=" + options.keepAliveInterval().toMillis() + "ms",
					"--spring.acp.agent.transport.http.max-post-body-size=" + options.maxPostBodyBytes() + "B",
					"--spring.acp.agent.transport.http.shutdown-timeout=" + options.shutdownTimeout().toMillis() + "ms",
					"--spring.acp.agent.transport.http.allowed-origins=" + String.join(",", options.allowedOrigins()),
					// Compression on, including SSE: the autoconfiguration takes text/event-stream out.
					"--server.compression.enabled=true", "--server.compression.min-response-size=1B",
					"--server.compression.mime-types=text/event-stream,application/json,text/plain");
		int port = ((WebServerApplicationContext) context).getWebServer().getPort();
		URI endpoint = URI.create("http://127.0.0.1:" + port + "/acp");
		return new Host() {

			@Override
			public URI endpoint() {
				return endpoint;
			}

			@Override
			public void stop() {
				// Boot's own shutdown: graceful, with the endpoint drained in an earlier phase.
				context.close();
			}

		};
	}

	@Test
	void anApplicationRouteSharesThePortWithAcp() throws Exception {
		Host host = startHost(standardConfig());
		try {
			URI hello = host.endpoint().resolve("/hello");
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(hello).GET().build(), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).isEqualTo("hello");
			assertThat(HttpProbes.initialize(host.endpoint(), null).statusCode()).isEqualTo(200);
			assertThat(HttpProbes.webSocketHandshake(host.endpoint(), null)).isEqualTo(HttpProbes.SWITCHING_PROTOCOLS);
		}
		finally {
			host.stop();
		}
	}

	@Test
	void anSseStreamIsNotCompressedForAClientThatAcceptsGzip() throws Exception {
		Host host = startHost(standardConfig());
		try {
			String connectionId = HttpProbes.initialize(host.endpoint(), null)
				.headers()
				.firstValue("Acp-Connection-Id")
				.orElseThrow();
			HttpRequest get = HttpRequest.newBuilder(host.endpoint())
				.GET()
				.header("Accept", "text/event-stream")
				.header("Accept-Encoding", "gzip")
				.header("Acp-Connection-Id", connectionId)
				.build();
			HttpResponse<InputStream> stream = HttpClient.newHttpClient()
				.sendAsync(get, HttpResponse.BodyHandlers.ofInputStream())
				.get(10, TimeUnit.SECONDS);
			try (InputStream body = stream.body()) {
				assertThat(stream.statusCode()).isEqualTo(200);
				assertThat(stream.headers().firstValue("Content-Encoding")).isEmpty();
				byte[] opening = ": connected".getBytes(StandardCharsets.UTF_8);
				assertThat(body.readNBytes(opening.length)).isEqualTo(opening);
			}
		}
		finally {
			host.stop();
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class TckApplication {

		@Bean
		RouterFunction<ServerResponse> hello() {
			return RouterFunctions.route(RequestPredicates.GET("/hello"), request -> ServerResponse.ok().bodyValue("hello"));
		}

	}

}
