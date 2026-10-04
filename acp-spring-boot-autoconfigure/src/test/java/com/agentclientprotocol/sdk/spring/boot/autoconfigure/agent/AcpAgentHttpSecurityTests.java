/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.http.server.AcpHttpEndpoint;
import com.agentclientprotocol.sdk.http.server.AcpHttpExchange;
import com.agentclientprotocol.sdk.http.server.AcpHttpReply;
import com.agentclientprotocol.sdk.http.server.AcpWsHandshake;
import com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent.AcpAgentHttpAutoConfigurationTests.EchoAgentConfiguration;
import com.agentclientprotocol.sdk.test.http.HttpProbes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's own Spring Security protects {@code /acp}, over HTTP and on the WebSocket
 * handshake, because the endpoint is mounted on the application's server and filter chain: an
 * unauthenticated request is refused, an authenticated one is served, and the authenticated
 * principal reaches the endpoint ({@link AcpHttpExchange#principal()}).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.autoconfigure.exclude=", "spring.acp.agent.transport.type=http" })
class AcpAgentHttpSecurityTests {

	private static final Map<String, String> ALICE = Map.of("Authorization",
			"Basic " + Base64.getEncoder().encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8)));

	@LocalServerPort
	private int port;

	@Autowired
	private PrincipalRecorder recorder;

	private URI endpoint;

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
		SecurityFilterChain acpSecurity(HttpSecurity http) throws Exception {
			return http.authorizeHttpRequests(requests -> requests.requestMatchers("/acp").authenticated())
				.httpBasic(Customizer.withDefaults())
				// ACP clients are not browsers: no CSRF token; the Origin check guards browsers.
				.csrf(csrf -> csrf.ignoringRequestMatchers("/acp"))
				.build();
		}

		@Bean
		UserDetailsService users() {
			return new InMemoryUserDetailsManager(
					User.withUsername("alice").password("{noop}secret").roles("USER").build());
		}

		@Bean
		static PrincipalRecorder principalRecorder() {
			return new PrincipalRecorder();
		}

	}

	/** Wraps the endpoint bean to record the principal of every exchange it is handed. */
	static class PrincipalRecorder implements BeanPostProcessor {

		final List<String> principals = new CopyOnWriteArrayList<>();

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (bean instanceof AcpHttpEndpoint endpoint) {
				return new Recording(endpoint);
			}
			return bean;
		}

		private void record(AcpHttpExchange exchange) {
			Principal principal = exchange.principal();
			this.principals.add(principal != null ? principal.getName() : "<none>");
		}

		private final class Recording implements AcpHttpEndpoint {

			private final AcpHttpEndpoint delegate;

			Recording(AcpHttpEndpoint delegate) {
				this.delegate = delegate;
			}

			@Override
			public StreamableHttpAcpAgentTransportOptions options() {
				return delegate.options();
			}

			@Override
			public Mono<AcpHttpReply> handle(AcpHttpExchange exchange) {
				record(exchange);
				return delegate.handle(exchange);
			}

			@Override
			public AcpWsHandshake webSocketHandshake(AcpHttpExchange handshake) {
				record(handshake);
				return delegate.webSocketHandshake(handshake);
			}

			@Override
			public void start() {
				delegate.start();
			}

			@Override
			public Mono<Void> closeGracefully() {
				return delegate.closeGracefully();
			}

			@Override
			public int activeConnectionCount() {
				return delegate.activeConnectionCount();
			}

			@Override
			public void setExceptionHandler(Consumer<Throwable> handler) {
				delegate.setExceptionHandler(handler);
			}

		}

	}

}
