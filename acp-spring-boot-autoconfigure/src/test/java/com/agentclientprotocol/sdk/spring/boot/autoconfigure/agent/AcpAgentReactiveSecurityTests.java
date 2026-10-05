/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import com.agentclientprotocol.sdk.http.server.AcpHttpExchange;
import com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent.AcpAgentHttpAutoConfigurationTests.EchoAgentConfiguration;
import com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent.AcpAgentHttpSecurityTests.PrincipalRecorder;
import com.agentclientprotocol.sdk.test.http.HttpProbes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In a reactive application the application's own {@link SecurityWebFilterChain} protects
 * {@code /acp}, over HTTP and on the WebSocket handshake, because the endpoint is a route on the
 * application's server behind its {@code WebFilter}s: an unauthenticated request is refused, an
 * authenticated one is served, and the authenticated principal reaches the endpoint
 * ({@link AcpHttpExchange#principal()}).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "spring.autoconfigure.exclude=",
		"spring.main.web-application-type=reactive", "spring.acp.agent.transport.type=http" })
class AcpAgentReactiveSecurityTests {

	private static final Map<String, String> ALICE = Map.of("Authorization",
			"Basic " + Base64.getEncoder().encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8)));

	@LocalServerPort
	private int port;

	@Autowired
	private PrincipalRecorder recorder;

	private URI endpoint = URI.create("http://127.0.0.1/acp");

	@BeforeEach
	void endpoint() {
		this.endpoint = URI.create("http://127.0.0.1:" + this.port + "/acp");
		this.recorder.principals.clear();
	}

	@Test
	void anUnauthenticatedRequestIsRefusedOverHttpAndOnTheHandshake() throws Exception {
		assertThat(HttpProbes.initialize(this.endpoint, null).statusCode()).isEqualTo(401);
		assertThat(HttpProbes.webSocketHandshake(this.endpoint, null)).isEqualTo(401);
		assertThat(this.recorder.principals).as("nothing reached the endpoint").isEmpty();
	}

	@Test
	void anAuthenticatedRequestIsServedAndItsPrincipalReachesTheEndpoint() throws Exception {
		assertThat(HttpProbes.initialize(this.endpoint, null, ALICE).statusCode()).isEqualTo(200);
		assertThat(HttpProbes.webSocketHandshake(this.endpoint, null, ALICE)).isEqualTo(HttpProbes.SWITCHING_PROTOCOLS);
		assertThat(this.recorder.principals).hasSize(2).allSatisfy(name -> assertThat(name).isEqualTo("alice"));
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(EchoAgentConfiguration.class)
	static class SecuredApplication {

		@Bean
		SecurityWebFilterChain acpSecurity(ServerHttpSecurity http) {
			return http.authorizeExchange(exchanges -> exchanges.pathMatchers("/acp").authenticated().anyExchange().permitAll())
				.httpBasic(Customizer.withDefaults())
				// ACP clients are not browsers: no CSRF token; the Origin check guards browsers.
				.csrf(csrf -> csrf.requireCsrfProtectionMatcher(
						new NegatedServerWebExchangeMatcher(ServerWebExchangeMatchers.pathMatchers("/acp"))))
				.build();
		}

		@Bean
		MapReactiveUserDetailsService users() {
			return new MapReactiveUserDetailsService(
					User.withUsername("alice").password("{noop}secret").roles("USER").build());
		}

		@Bean
		static PrincipalRecorder principalRecorder() {
			return new PrincipalRecorder();
		}

	}

}
