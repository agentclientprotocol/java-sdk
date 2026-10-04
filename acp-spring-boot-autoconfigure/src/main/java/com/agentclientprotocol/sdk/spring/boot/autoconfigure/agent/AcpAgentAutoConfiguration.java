/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery.AgentCandidate;
import com.agentclientprotocol.sdk.integration.AcpAgentHost;
import com.agentclientprotocol.sdk.integration.AcpAgents;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.util.ClassUtils;

/**
 * Serves the application's single {@code @AcpAgent} bean. The bean's handlers are found on its
 * user class (a CGLIB proxy keeps its advice), and {@code AcpInterceptor},
 * {@code ArgumentResolver} and {@code ReturnValueHandler} beans are added in their order.
 */
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
				AcpAgentProperties properties, ObjectProvider<AcpInterceptor> interceptors,
				ObjectProvider<ArgumentResolver> resolvers, ObjectProvider<ReturnValueHandler> returnValueHandlers) {
			AcpAgentSupport agent = builder(applicationContext, properties, interceptors, resolvers,
					returnValueHandlers)
				.transport(transport)
				.build();
			Runnable onTransportEnd = () -> {
			};
			if (properties.isShutdownOnTransportEnd()
					&& applicationContext instanceof ConfigurableApplicationContext configurable) {
				onTransportEnd = () -> closeContext(configurable);
			}
			return new AcpAgentLifecycle(new AcpAgentHost(agent, onTransportEnd));
		}

	}

	// Listener-backed transports (Streamable HTTP) host one agent runtime per remote
	// connection, each dispatching to the same @AcpAgent bean, so its handlers must be
	// thread-safe.
	@Bean
	@ConditionalOnBean(annotation = AcpAgent.class)
	@ConditionalOnMissingBean
	AcpAgentFactory acpAgentFactory(ApplicationContext applicationContext, AcpAgentProperties properties,
			ObjectProvider<AcpInterceptor> interceptors, ObjectProvider<ArgumentResolver> resolvers,
			ObjectProvider<ReturnValueHandler> returnValueHandlers) {
		return builder(applicationContext, properties, interceptors, resolvers, returnValueHandlers).buildFactory();
	}

	private static AcpAgentSupport.Builder builder(ApplicationContext context, AcpAgentProperties properties,
			ObjectProvider<AcpInterceptor> interceptors, ObjectProvider<ArgumentResolver> resolvers,
			ObjectProvider<ReturnValueHandler> returnValueHandlers) {
		AgentCandidate<?> agent = findAgent(context);
		logger.info("Discovered @AcpAgent bean: {}", agent.userClass().getName());
		return AcpAgents.builder(agent, properties.toSettings(), interceptors.orderedStream().toList(),
				resolvers.orderedStream().toList(), returnValueHandlers.orderedStream().toList());
	}

	private static AgentCandidate<?> findAgent(ApplicationContext context) {
		List<AgentCandidate<?>> candidates = Arrays.stream(context.getBeanNamesForAnnotation(AcpAgent.class))
			.<AgentCandidate<?>>map(name -> candidate(context, name))
			.toList();
		try {
			return AcpAgentDiscovery.requireSingle(candidates, "spring.acp.agent.enabled")
				.orElseThrow(() -> new IllegalStateException("No @AcpAgent bean"));
		}
		catch (IllegalStateException ex) {
			throw new BeanCreationException(String.valueOf(ex.getMessage()));
		}
	}

	private static AgentCandidate<Object> candidate(ApplicationContext context, String name) {
		Object bean = context.getBean(name);
		// The user class, not a CGLIB proxy's: the proxy's methods carry no annotations.
		@SuppressWarnings("unchecked")
		Class<Object> userClass = (Class<Object>) ClassUtils.getUserClass(bean);
		return new AgentCandidate<>(name, userClass, () -> bean);
	}

	private static void closeContext(ConfigurableApplicationContext context) {
		if (context.isActive()) {
			logger.info("ACP agent transport ended; closing the application context");
			context.close();
		}
	}

	/**
	 * Starts and stops the agent with the context. When the transport ends on its own
	 * (for stdio: the client closed the agent's input and every reply has been written),
	 * the agent has no one left to serve, so the host closes the application context,
	 * which lets a {@code spring.main.keep-alive} application exit.
	 */
	static class AcpAgentLifecycle implements SmartLifecycle {

		private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

		private final AcpAgentHost host;

		private volatile boolean running = false;

		AcpAgentLifecycle(AcpAgentHost host) {
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
