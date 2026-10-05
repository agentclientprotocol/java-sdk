/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.net.URI;

import com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent.AcpAgentHttpAutoConfigurationTests.EchoAgentConfiguration;
import com.agentclientprotocol.sdk.test.http.HttpProbes;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's own HTTP server observations cover {@code /acp}: the endpoint is mounted on
 * the application's server and filter chain, so Spring's {@code ServerHttpObservationFilter}
 * records an {@code http.server.requests} observation for an ACP request as for any other.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "spring.acp.agent.transport.type=http")
class AcpAgentHttpObservationTests {

	@LocalServerPort
	private int port;

	@Autowired
	private TestObservationRegistry observations;

	@Test
	void anAcpRequestIsObserved() throws Exception {
		URI endpoint = URI.create("http://127.0.0.1:" + this.port + "/acp");
		assertThat(HttpProbes.initialize(endpoint, null).statusCode()).isEqualTo(200);
		TestObservationRegistryAssert.assertThat(this.observations)
			.hasObservationWithNameEqualTo("http.server.requests")
			.that()
			.hasLowCardinalityKeyValue("method", "POST")
			.hasLowCardinalityKeyValue("status", "200")
			.hasBeenStopped();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(EchoAgentConfiguration.class)
	static class ObservedApplication {

		@Bean
		TestObservationRegistry observationRegistry() {
			return TestObservationRegistry.create();
		}

	}

}
