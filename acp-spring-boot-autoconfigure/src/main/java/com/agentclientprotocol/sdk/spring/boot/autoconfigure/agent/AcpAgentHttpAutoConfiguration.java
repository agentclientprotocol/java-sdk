/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.time.Duration;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import com.agentclientprotocol.sdk.integration.AcpAgentSettings;
import com.agentclientprotocol.sdk.integration.AcpListenerHost;
import com.agentclientprotocol.sdk.integration.AcpListeners;
import com.agentclientprotocol.sdk.integration.AcpServletHost;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Serves the {@code @AcpAgent} bean over ACP Streamable HTTP when
 * {@code spring.acp.agent.transport.type=http} (or {@code websocket}, the same) and
 * {@code acp-streamable-http-jetty} is on the classpath. One agent runtime per remote
 * connection, from the {@link AcpAgentFactory}.
 * <p>
 * In a servlet web application the {@link StreamableHttpAcpServlet} is mounted on the
 * application's own server (HTTP/SSE). Otherwise the SDK's
 * {@link StreamableHttpAcpAgentTransport} runs its own listener on
 * {@code transport.http.listener.port}, which also accepts WebSocket upgrades on the same
 * path and cleartext HTTP/2.
 */
@AutoConfiguration(after = AcpAgentAutoConfiguration.class)
@ConditionalOnClass(StreamableHttpAcpServlet.class)
@ConditionalOnProperty(prefix = "spring.acp.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@Conditional(OnHttpAgentTransportCondition.class)
@ConditionalOnBean(AcpAgentFactory.class)
@EnableConfigurationProperties(AcpAgentProperties.class)
public class AcpAgentHttpAutoConfiguration {

	/** How long closing the endpoint may take, beyond its own shutdown timeout. */
	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	static class ServletConfiguration {

		@Bean
		@ConditionalOnMissingBean(name = "acpServletRegistration")
		ServletRegistrationBean<StreamableHttpAcpServlet> acpServletRegistration(AcpAgentFactory agentFactory,
				AcpAgentProperties properties) {
			AcpAgentSettings settings = properties.toSettings();
			ServletRegistrationBean<StreamableHttpAcpServlet> registration = new ServletRegistrationBean<>(
					AcpListeners.servlet(settings, agentFactory), settings.http().path());
			registration.setName("acp");
			registration.setAsyncSupported(true);
			return registration;
		}

		@Bean
		AcpServletLifecycle acpServletLifecycle(
				@Qualifier("acpServletRegistration") ServletRegistrationBean<?> acpServletRegistration) {
			return new AcpServletLifecycle(acpServletRegistration);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnNotWebApplication
	static class ListenerConfiguration {

		@Bean
		@ConditionalOnMissingBean
		StreamableHttpAcpAgentTransport streamableHttpAcpAgentTransport(AcpAgentFactory agentFactory,
				AcpAgentProperties properties) {
			return AcpListeners.listener(properties.toSettings(), agentFactory);
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
