/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import com.agentclientprotocol.sdk.http.server.AcpHttpEndpoint;
import com.agentclientprotocol.sdk.http.server.AcpHttpReply;
import com.agentclientprotocol.sdk.integration.AcpAgentSettings;
import com.agentclientprotocol.sdk.integration.AcpListenerHost;
import com.agentclientprotocol.sdk.integration.AcpListeners;
import com.agentclientprotocol.sdk.integration.AcpServletHost;
import com.agentclientprotocol.sdk.http.webflux.AcpWebFluxHost;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory;
import org.springframework.boot.web.server.Compression;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Serves the application's {@code @AcpAgent} bean over ACP Streamable HTTP and WebSocket when
 * {@code spring.acp.agent.transport.type} is {@code http} (or {@code websocket}, the same) and an
 * SDK host for the kind of application is on the classpath. The starter brings none, so add the
 * one that fits: {@code acp-http-servlet} for a servlet (Spring MVC) web application,
 * {@code acp-http-webflux} for a reactive (WebFlux) one, or {@code acp-streamable-http-jetty}
 * for the SDK's own listener in a non-web application. Each client connection gets its own agent
 * from the {@link AcpAgentFactory} that {@link AcpAgentAutoConfiguration} creates, and every one
 * of them calls the same bean.
 *
 * <p>Where the endpoint is served depends on the kind of application:
 * <ul>
 * <li>A servlet web application mounts the {@link StreamableHttpAcpServlet} on its own server
 * ({@code server.port}) at {@code spring.acp.agent.transport.http.path}: Streamable HTTP, SSE and
 * WebSocket upgrades on that one path, through the application's filter chain, so Spring
 * Security, observations and access logs apply to ACP as to any other endpoint. A bean named
 * {@code acpServletRegistration} of the application's own replaces the registration.</li>
 * <li>A reactive web application routes the path to the endpoint with a {@code RouterFunction}
 * bean named {@code acpRouterFunction} ({@link AcpWebFluxHost}) on its own server
 * ({@code server.port}, Reactor Netty by default), so the application's {@code WebFilter}s, its
 * {@code SecurityWebFilterChain} included, apply to it over HTTP and on the WebSocket handshake.
 * A bean of that name of the application's own replaces the route. Without
 * {@code acp-http-webflux} on the classpath the startup fails, naming it.</li>
 * <li>A non-web application gets the SDK's {@link StreamableHttpAcpAgentTransport}, listening on
 * {@code spring.acp.agent.transport.http.listener.port} (default 8080) with HTTP/1.1, cleartext
 * HTTP/2 and WebSocket upgrades on the same path. It starts with the context and stops with it,
 * waiting at most 30 seconds. A {@code StreamableHttpAcpAgentTransport} bean of the application's
 * own replaces it.</li>
 * </ul>
 * In a web application the endpoint itself is an {@link AcpHttpEndpoint} bean, which an
 * application may wrap or replace. Before the server's graceful shutdown, in an earlier
 * {@code SmartLifecycle} phase, it drains: SSE streams get a closing comment and complete,
 * WebSockets close with 1001, so the shutdown never waits for them. With
 * {@code server.compression} on, {@code text/event-stream} is taken out of the compressed types,
 * since a compressed SSE stream is held back until the buffer fills. The endpoint's limits come
 * from {@code spring.acp.agent.transport.http.*} ({@link AcpAgentProperties.AgentHttpProperties}).
 * Nothing here applies when {@code spring.acp.agent.enabled=false}.
 */
@AutoConfiguration(after = AcpAgentAutoConfiguration.class)
@ConditionalOnClass(AcpHttpEndpoint.class)
@ConditionalOnProperty(prefix = "spring.acp.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@Conditional(OnHttpAgentTransportCondition.class)
@ConditionalOnBean(AcpAgentFactory.class)
@EnableConfigurationProperties(AcpAgentProperties.class)
public class AcpAgentHttpAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(AcpAgentHttpAutoConfiguration.class);

	/** How long closing the endpoint may take, beyond its own shutdown timeout. */
	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	@ConditionalOnClass(StreamableHttpAcpServlet.class)
	static class ServletConfiguration {

		@Bean
		@ConditionalOnMissingBean
		AcpHttpEndpoint acpHttpEndpoint(AcpAgentFactory agentFactory, AcpAgentProperties properties,
				ApplicationContext context) {
			WebSocketApiCheck.requireCompatible(context.getClassLoader());
			return AcpListeners.endpoint(properties.toSettings(), agentFactory);
		}

		@Bean
		@ConditionalOnMissingBean(name = "acpServletRegistration")
		ServletRegistrationBean<StreamableHttpAcpServlet> acpServletRegistration(AcpHttpEndpoint acpHttpEndpoint,
				AcpAgentProperties properties) {
			AcpAgentSettings settings = properties.toSettings();
			ServletRegistrationBean<StreamableHttpAcpServlet> registration = new ServletRegistrationBean<>(
					new StreamableHttpAcpServlet(acpHttpEndpoint), settings.http().path());
			registration.setName("acp");
			registration.setAsyncSupported(true);
			return registration;
		}

		@Bean
		@ConditionalOnClass(name = "org.springframework.boot.web.server.AbstractConfigurableWebServerFactory")
		static SseCompressionExclusion acpSseCompressionExclusion() {
			return new SseCompressionExclusion();
		}

		@Bean
		AcpServletLifecycle acpServletLifecycle(
				@Qualifier("acpServletRegistration") ServletRegistrationBean<?> acpServletRegistration) {
			return new AcpServletLifecycle(acpServletRegistration);
		}

	}

	/**
	 * Takes {@code text/event-stream} out of the server's compressed types when
	 * {@code server.compression} is on: a compressing output stream holds SSE events back until
	 * its buffer fills, and proxies then cut the stream. Runs after Boot's own customizer, which
	 * applies the properties.
	 */
	static class SseCompressionExclusion
			implements WebServerFactoryCustomizer<AbstractConfigurableWebServerFactory>, Ordered {

		@Override
		public void customize(AbstractConfigurableWebServerFactory factory) {
			Compression compression = factory.getCompression();
			if (compression == null || !compression.getEnabled()) {
				return;
			}
			compression.setMimeTypes(withoutEventStream(compression.getMimeTypes()));
			compression.setAdditionalMimeTypes(withoutEventStream(compression.getAdditionalMimeTypes()));
		}

		private static String[] withoutEventStream(String @Nullable [] types) {
			if (types == null) {
				return new String[0];
			}
			return Arrays.stream(types)
				.filter(type -> !type.trim().toLowerCase(Locale.ROOT).startsWith(AcpHttpReply.EVENT_STREAM))
				.toArray(String[]::new);
		}

		@Override
		public int getOrder() {
			return Ordered.LOWEST_PRECEDENCE;
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
	@ConditionalOnClass(AcpWebFluxHost.class)
	static class ReactiveConfiguration {

		@Bean
		@ConditionalOnMissingBean
		AcpHttpEndpoint acpHttpEndpoint(AcpAgentFactory agentFactory, AcpAgentProperties properties) {
			return AcpHttpEndpoint.create(AcpJsonMapper.createDefault(), agentFactory,
					properties.toSettings().toOptions(false));
		}

		@Bean
		@ConditionalOnMissingBean(name = "acpRouterFunction")
		RouterFunction<ServerResponse> acpRouterFunction(AcpHttpEndpoint acpHttpEndpoint, AcpAgentProperties properties) {
			return new AcpWebFluxHost(acpHttpEndpoint).routerFunction(properties.toSettings().http().path());
		}

		@Bean
		@ConditionalOnClass(name = "org.springframework.boot.web.server.AbstractConfigurableWebServerFactory")
		static SseCompressionExclusion acpSseCompressionExclusion() {
			return new SseCompressionExclusion();
		}

		@Bean
		AcpEndpointLifecycle acpEndpointLifecycle(AcpHttpEndpoint acpHttpEndpoint) {
			return new AcpEndpointLifecycle(acpHttpEndpoint);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
	@ConditionalOnMissingClass("com.agentclientprotocol.sdk.http.webflux.AcpWebFluxHost")
	static class MissingWebFluxHostConfiguration {

		MissingWebFluxHostConfiguration() {
			throw new IllegalStateException("The ACP HTTP transport in a reactive (WebFlux) web application needs "
					+ "acp-http-webflux on the classpath");
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnNotWebApplication
	@ConditionalOnClass(StreamableHttpAcpAgentTransport.class)
	static class ListenerConfiguration {

		@Bean
		@ConditionalOnMissingBean
		StreamableHttpAcpAgentTransport streamableHttpAcpAgentTransport(AcpAgentFactory agentFactory,
				AcpAgentProperties properties, ApplicationContext context) {
			// Virtual threads only when the application opted in, on the same executor as the
			// handlers by default; otherwise Jetty's platform pool.
			return AcpListeners.listener(properties.toSettings(), agentFactory, HandlerExecutors.listenerThreads(context));
		}

		@Bean
		AcpAgentHttpListenerLifecycle acpAgentHttpListenerLifecycle(StreamableHttpAcpAgentTransport transport) {
			return new AcpAgentHttpListenerLifecycle(new AcpListenerHost(transport));
		}

	}

	/**
	 * Closes the servlet's ACP connections before the web server shuts down. Each holds an
	 * open SSE response, which Boot's graceful shutdown counts as an active request and
	 * waits for, up to {@code spring.lifecycle.timeout-per-shutdown-phase} (30 seconds by
	 * default). The servlet API gives the SDK no hook before that wait, so the application
	 * closes them ({@link AcpServletHost}).
	 */
	static class AcpServletLifecycle implements SmartLifecycle {

		private final ServletRegistrationBean<?> registration;

		private volatile boolean running = false;

		AcpServletLifecycle(ServletRegistrationBean<?> registration) {
			this.registration = registration;
		}

		@Override
		public void start() {
			running = true;
		}

		@Override
		public void stop() {
			if (registration.getServlet() instanceof StreamableHttpAcpServlet servlet) {
				AcpServletHost.closeBeforeShutdown(servlet, STOP_TIMEOUT);
			}
			running = false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public int getPhase() {
			// Stops before graceful shutdown (DEFAULT_PHASE - 1024) and the web server
			// stop (DEFAULT_PHASE - 2048).
			return SmartLifecycle.DEFAULT_PHASE;
		}

	}

	/**
	 * Starts the endpoint with the context, and closes its connections before the web server
	 * shuts down: each holds an open SSE response or WebSocket, which the server's graceful
	 * shutdown would wait for, up to {@code spring.lifecycle.timeout-per-shutdown-phase}.
	 */
	static class AcpEndpointLifecycle implements SmartLifecycle {

		private final AcpHttpEndpoint endpoint;

		private volatile boolean running = false;

		AcpEndpointLifecycle(AcpHttpEndpoint endpoint) {
			this.endpoint = endpoint;
		}

		@Override
		public void start() {
			endpoint.start();
			running = true;
		}

		@Override
		public void stop() {
			try {
				endpoint.closeGracefully().block(STOP_TIMEOUT);
			}
			catch (RuntimeException ex) {
				logger.warn("ACP HTTP connections did not close within {}: {}", STOP_TIMEOUT, ex.getMessage());
			}
			running = false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public int getPhase() {
			// Stops before graceful shutdown (DEFAULT_PHASE - 1024) and the web server
			// stop (DEFAULT_PHASE - 2048).
			return SmartLifecycle.DEFAULT_PHASE;
		}

	}

	static class AcpAgentHttpListenerLifecycle implements SmartLifecycle {

		private final AcpListenerHost host;

		private volatile boolean running = false;

		AcpAgentHttpListenerLifecycle(AcpListenerHost host) {
			this.host = host;
		}

		@Override
		public void start() {
			host.start();
			running = true;
		}

		@Override
		public void stop() {
			host.stop(STOP_TIMEOUT);
			running = false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

	}

}
