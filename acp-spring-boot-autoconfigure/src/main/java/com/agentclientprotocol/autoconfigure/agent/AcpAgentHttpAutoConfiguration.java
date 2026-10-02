package com.agentclientprotocol.autoconfigure.agent;

import java.time.Duration;

import com.agentclientprotocol.autoconfigure.agent.AcpAgentProperties.AgentHttpProperties;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpServlet;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;

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
import org.springframework.context.annotation.Configuration;

/**
 * Serves the {@code @AcpAgent} bean over ACP Streamable HTTP when
 * {@code spring.acp.agent.transport.type=http} and {@code acp-streamable-http-jetty} is
 * on the classpath. One agent runtime per remote connection, from the
 * {@link AcpAgentFactory}.
 * <p>
 * In a servlet web application the {@link StreamableHttpAcpServlet} is mounted on the
 * application's own server (HTTP/SSE). Otherwise the SDK's
 * {@link StreamableHttpAcpAgentTransport} runs its own listener, which also accepts
 * WebSocket upgrades on the same path and cleartext HTTP/2.
 */
@AutoConfiguration(after = AcpAgentAutoConfiguration.class)
@ConditionalOnClass(StreamableHttpAcpServlet.class)
@ConditionalOnProperty(prefix = "spring.acp.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "spring.acp.agent.transport", name = "type", havingValue = "http")
@ConditionalOnBean(AcpAgentFactory.class)
@EnableConfigurationProperties(AcpAgentProperties.class)
public class AcpAgentHttpAutoConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	static class ServletConfiguration {

		@Bean
		@ConditionalOnMissingBean(name = "acpServletRegistration")
		ServletRegistrationBean<StreamableHttpAcpServlet> acpServletRegistration(AcpAgentFactory agentFactory,
				AcpAgentProperties properties) {
			AgentHttpProperties http = properties.getTransport().getHttp();
			StreamableHttpAcpServlet servlet = new StreamableHttpAcpServlet(AcpJsonMapper.createDefault(), agentFactory,
					options(http));
			ServletRegistrationBean<StreamableHttpAcpServlet> registration = new ServletRegistrationBean<>(servlet,
					http.getPath());
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
			AgentHttpProperties http = properties.getTransport().getHttp();
			return new StreamableHttpAcpAgentTransport(http.getPort(), http.getPath(), AcpJsonMapper.createDefault(),
					agentFactory, options(http));
		}

		@Bean
		AcpAgentHttpListenerLifecycle acpAgentHttpListenerLifecycle(StreamableHttpAcpAgentTransport transport) {
			return new AcpAgentHttpListenerLifecycle(transport);
		}

	}

	static StreamableHttpAcpAgentTransportOptions options(AgentHttpProperties http) {
		var options = StreamableHttpAcpAgentTransportOptions.builder();
		if (http.getMaxPostBodySize() != null) {
			options.maxPostBodyBytes(http.getMaxPostBodySize().toBytes());
		}
		if (http.getKeepAliveInterval() != null) {
			options.keepAliveInterval(http.getKeepAliveInterval());
		}
		if (http.getMailboxCapacity() != null) {
			options.mailboxCapacity(http.getMailboxCapacity());
		}
		if (http.getMaxPendingSseEvents() != null) {
			options.maxPendingSseEvents(http.getMaxPendingSseEvents());
		}
		if (http.getMaxWebSocketPendingFrames() != null) {
			options.maxWebSocketPendingFrames(http.getMaxWebSocketPendingFrames());
		}
		if (http.getMaxProvisionalSessions() != null) {
			options.maxProvisionalSessions(http.getMaxProvisionalSessions());
		}
		if (http.getMaxConcurrentStreamsPerConnection() != null) {
			options.maxConcurrentStreamsPerConnection(http.getMaxConcurrentStreamsPerConnection());
		}
		return options.build();
	}

	/**
	 * Closes the servlet's ACP connections before the web server shuts down. Each holds
	 * an open SSE response; left to the servlet's {@code destroy()}, which runs after the
	 * server has stopped, closing them waits out the servlet's 30 second timeout, and
	 * graceful shutdown would wait on them as in-flight requests.
	 */
	static class AcpServletLifecycle implements SmartLifecycle {

		private static final Duration TIMEOUT = Duration.ofSeconds(30);

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
				servlet.closeGracefully().block(TIMEOUT);
			}
			running = false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

		@Override
		public int getPhase() {
			// After the default phase stops nothing else; before graceful shutdown
			// (DEFAULT_PHASE - 1024) and the web server stop (DEFAULT_PHASE - 2048).
			return SmartLifecycle.DEFAULT_PHASE;
		}

	}

	static class AcpAgentHttpListenerLifecycle implements SmartLifecycle {

		private static final Duration TIMEOUT = Duration.ofSeconds(30);

		private final StreamableHttpAcpAgentTransport transport;

		private volatile boolean running = false;

		AcpAgentHttpListenerLifecycle(StreamableHttpAcpAgentTransport transport) {
			this.transport = transport;
		}

		@Override
		public void start() {
			transport.start().block(TIMEOUT);
			running = true;
		}

		@Override
		public void stop() {
			transport.closeGracefully().block(TIMEOUT);
			running = false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

	}

}
