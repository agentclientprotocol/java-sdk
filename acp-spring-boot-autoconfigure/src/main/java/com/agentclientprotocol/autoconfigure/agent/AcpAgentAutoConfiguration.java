package com.agentclientprotocol.autoconfigure.agent;

import java.util.List;
import java.util.Map;

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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = AcpAgentTransportAutoConfiguration.class)
@ConditionalOnClass(AcpAgentSupport.class)
@ConditionalOnBean(AcpAgentTransport.class)
@EnableConfigurationProperties(AcpAgentProperties.class)
public class AcpAgentAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(AcpAgentAutoConfiguration.class);

	@Bean
	AcpAgentLifecycle acpAgentLifecycle(ApplicationContext applicationContext, AcpAgentTransport transport,
			AcpAgentProperties properties, List<AcpInterceptor> interceptors) {

		Map<String, Object> agentBeans = applicationContext.getBeansWithAnnotation(AcpAgent.class);

		if (agentBeans.isEmpty()) {
			throw new BeanCreationException(
					"No @AcpAgent-annotated bean found in the application context. "
							+ "Define a Spring bean annotated with @AcpAgent to use ACP agent autoconfiguration.");
		}
		if (agentBeans.size() > 1) {
			throw new BeanCreationException(
					"Found " + agentBeans.size() + " @AcpAgent-annotated beans " + agentBeans.keySet()
							+ ", but only one is supported per application.");
		}

		Object agentBean = agentBeans.values().iterator().next();
		logger.info("Discovered @AcpAgent bean: {}", agentBean.getClass().getName());

		var builder = AcpAgentSupport.create(agentBean)
			.transport(transport)
			.requestTimeout(properties.getRequestTimeout());

		for (AcpInterceptor interceptor : interceptors) {
			builder.interceptor(interceptor);
		}

		return new AcpAgentLifecycle(builder.build());
	}

	static class AcpAgentLifecycle implements SmartLifecycle {

		private final AcpAgentSupport agentSupport;

		private volatile boolean running = false;

		AcpAgentLifecycle(AcpAgentSupport agentSupport) {
			this.agentSupport = agentSupport;
		}

		@Override
		public void start() {
			agentSupport.start();
			running = true;
		}

		@Override
		public void stop() {
			agentSupport.close();
			running = false;
		}

		@Override
		public boolean isRunning() {
			return running;
		}
	}
}
