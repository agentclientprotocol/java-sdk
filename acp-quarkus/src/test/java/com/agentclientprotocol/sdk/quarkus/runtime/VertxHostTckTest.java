/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.quarkus.AcpBuildTimeConfig;
import com.agentclientprotocol.sdk.test.http.AcpHttpTransportTck;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import org.mockito.Answers;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The extension's Vert.x host on a plain Vert.x server and router, under the shared transport
 * TCK: the same route the extension mounts on the Quarkus router, without Quarkus around it.
 * {@code QuarkusTckTest} runs the same suite inside a Quarkus application.
 */
class VertxHostTckTest extends AcpHttpTransportTck {

	@Override
	protected Host startHost(HostConfig config) throws Exception {
		Vertx vertx = Vertx.vertx();
		var options = config.options();
		AcpAgentAssembly assembly = mock(AcpAgentAssembly.class);
		when(assembly.factory()).thenReturn(config.agents());
		AcpBuildTimeConfig buildTime = mock(AcpBuildTimeConfig.class, Answers.RETURNS_DEEP_STUBS);
		when(buildTime.agent().transport().http().path()).thenReturn("acp");
		AcpVertxHost host = new AcpVertxHost(assembly, options, buildTime);
		AcpHttpAgentHost agentHost = new AcpHttpAgentHost(host);
		Router router = Router.router(vertx);
		host.register(router);
		HttpServer server = vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0))
			.requestHandler(router)
			.listen()
			.toCompletionStage()
			.toCompletableFuture()
			.get(10, TimeUnit.SECONDS);
		URI endpoint = URI.create("http://127.0.0.1:" + server.actualPort() + "/acp");
		return new Host() {

			@Override
			public URI endpoint() {
				return endpoint;
			}

			@Override
			public void stop() throws Exception {
				// As the extension does at shutdown: drain, then the server stops.
				agentHost.drain();
				server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
				vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
			}

		};
	}


}
