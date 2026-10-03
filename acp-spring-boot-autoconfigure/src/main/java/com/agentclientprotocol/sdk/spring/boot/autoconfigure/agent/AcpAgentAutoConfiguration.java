package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration(after = AcpAgentTransportAutoConfiguration.class)
@ConditionalOnClass(AcpAgentSupport.class)
@EnableConfigurationProperties(AcpAgentProperties.class)
public class AcpAgentAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(AcpAgentAutoConfiguration.class);

	// Back off for client-only applications: only start an agent lifecycle when the
	// application actually defines an @AcpAgent bean. The acp-spring-boot-starter serves
	// both clients and agents, so a client app legitimately has no @AcpAgent bean.
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBean(AcpAgentTransport.class)
	static class SingleTransportAgentConfiguration {

		@Bean
		@ConditionalOnBean(annotation = AcpAgent.class)
		AcpAgentLifecycle acpAgentLifecycle(ApplicationContext applicationContext, AcpAgentTransport transport,
				AcpAgentProperties properties, List<AcpInterceptor> interceptors) {
			Object agentBean = findAgentBean(applicationContext);
			AcpAgentSupport agentSupport = agentSupportBuilder(agentBean, properties, interceptors).transport(transport)
				.build();
			ConfigurableApplicationContext contextToClose = (properties.isShutdownOnTransportEnd()
					&& applicationContext instanceof ConfigurableApplicationContext configurable) ? configurable : null;
			return new AcpAgentLifecycle(agentSupport, transport, contextToClose);
		}

	}

	// Listener-backed transports (Streamable HTTP) host one agent runtime per remote
	// connection, each dispatching to the same @AcpAgent bean, so its handlers must be
	// thread-safe.
	@Bean
	@ConditionalOnBean(annotation = AcpAgent.class)
	@ConditionalOnMissingBean
	AcpAgentFactory acpAgentFactory(ApplicationContext applicationContext, AcpAgentProperties properties,
			List<AcpInterceptor> interceptors) {
		return agentSupportBuilder(findAgentBean(applicationContext), properties, interceptors).buildFactory();
	}

	private static Object findAgentBean(ApplicationContext applicationContext) {
		Map<String, Object> agentBeans = applicationContext.getBeansWithAnnotation(AcpAgent.class);

		if (agentBeans.size() > 1) {
			throw new BeanCreationException("Found " + agentBeans.size() + " @AcpAgent-annotated beans "
					+ agentBeans.keySet() + ", but only one is supported per application.");
		}

		Object agentBean = agentBeans.values().iterator().next();
		logger.info("Discovered @AcpAgent bean: {}", agentBean.getClass().getName());
		return agentBean;
	}

	private static AcpAgentSupport.Builder agentSupportBuilder(Object agentBean, AcpAgentProperties properties,
			List<AcpInterceptor> interceptors) {
		var builder = AcpAgentSupport.create(agentBean).requestTimeout(properties.getRequestTimeout());

		for (AcpInterceptor interceptor : interceptors) {
			builder.interceptor(interceptor);
		}

		return builder;
	}

	/**
	 * Starts and stops the agent with the context. When the transport ends on its own
	 * (for stdio: the client closed the agent's input and every reply has been written),
	 * the agent has no one left to serve, so the lifecycle closes the application
	 * context, which lets a {@code spring.main.keep-alive} application exit.
	 */
	static class AcpAgentLifecycle implements SmartLifecycle {

		private final AcpAgentSupport agentSupport;

		private final AcpAgentTransport transport;

		private final ConfigurableApplicationContext contextToClose;

		private volatile boolean running = false;

		private volatile boolean stopping = false;

		AcpAgentLifecycle(AcpAgentSupport agentSupport, AcpAgentTransport transport,
				ConfigurableApplicationContext contextToClose) {
			this.agentSupport = agentSupport;
			this.transport = transport;
			this.contextToClose = contextToClose;
		}

		@Override
		public void start() {
			agentSupport.start();
			running = true;
			if (contextToClose != null) {
				transport.awaitTermination().subscribe(null, error -> closeContext(), this::closeContext);
			}
		}

		private void closeContext() {
			if (stopping || contextToClose == null || !contextToClose.isActive()) {
				return;
			}
			logger.info("ACP agent transport ended; closing the application context");
			// Not on the transport's thread: closing the context stops this lifecycle,
			// which closes the transport.
			Thread closer = new Thread(contextToClose::close, "acp-agent-shutdown");
			closer.start();
		}

		@Override
		public void stop() {
			stopping = true;
			agentSupport.close();
			running = false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}

	}

}
