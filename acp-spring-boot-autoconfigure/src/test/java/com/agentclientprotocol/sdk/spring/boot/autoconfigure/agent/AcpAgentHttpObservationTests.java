/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.net.URI;
import java.time.Duration;

import com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent.AcpAgentHttpAutoConfigurationTests.EchoAgentConfiguration;
import com.agentclientprotocol.sdk.test.http.HttpProbes;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.tck.TestObservationRegistry;
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
	private StoppedObservations stoppedObservations;

	@Test
	void anAcpRequestIsObserved() throws Exception {
		URI endpoint = URI.create("http://127.0.0.1:" + this.port + "/acp");
		assertThat(HttpProbes.initialize(endpoint, null).statusCode()).isEqualTo(200);
		// Waited for, not asserted at once: the server may stop its observation after the
		// response has reached the client. Selected by path, not taken first: the server may
		// observe other requests, such as another process probing its port. The endpoint is not
		// a mapped handler, so its low cardinality uri is UNKNOWN and the path is the http.url.
		this.stoppedObservations.await("http.server.requests", Duration.ofSeconds(10), KeyValue.of("method", "POST"),
				KeyValue.of("http.url", "/acp"), KeyValue.of("status", "200"));
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(EchoAgentConfiguration.class)
	static class ObservedApplication {

		@Bean
		TestObservationRegistry observationRegistry() {
			return TestObservationRegistry.create();
		}

		/** Registered with the registry by Spring Boot, as every observation handler bean. */
		@Bean
		StoppedObservations stoppedObservations() {
			return new StoppedObservations();
		}

	}

}
