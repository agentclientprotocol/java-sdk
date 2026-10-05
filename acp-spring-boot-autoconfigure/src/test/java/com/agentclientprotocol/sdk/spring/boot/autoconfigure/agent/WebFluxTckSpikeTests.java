/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.net.URI;
import java.time.Duration;

import com.agentclientprotocol.sdk.http.server.AcpHttpEndpoint;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.test.http.AcpHttpTransportTck;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

/**
 * SPIKE (not shipped): the WebFlux host on Reactor Netty under the shared transport TCK.
 */
class WebFluxTckSpikeTests extends AcpHttpTransportTck {

	@Override
	protected Host startHost(HostConfig config) {
		AcpHttpEndpoint endpoint = AcpHttpEndpoint.create(AcpJsonMapper.createDefault(), config.agents(),
				config.options());
		endpoint.start();
		AcpWebFluxHost host = new AcpWebFluxHost(endpoint);
		var handler = WebHttpHandlerBuilder.webHandler(exchange -> {
			if (exchange.getRequest().getPath().value().equals("/acp")) {
				return host.handle(exchange);
			}
			exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.NOT_FOUND);
			return exchange.getResponse().setComplete();
		}).build();
		DisposableServer server = HttpServer.create()
			.host("127.0.0.1")
			.port(0)
			.handle(new ReactorHttpHandlerAdapter(handler))
			.bindNow();
		URI uri = URI.create("http://127.0.0.1:" + server.port() + "/acp");
		return new Host() {

			@Override
			public URI endpoint() {
				return uri;
			}

			@Override
			public void stop() {
				endpoint.closeGracefully().block(Duration.ofSeconds(10));
				server.disposeNow(Duration.ofSeconds(10));
			}

		};
	}

}
