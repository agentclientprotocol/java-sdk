/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.ArrayList;
import java.util.List;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery.AgentCandidate;
import com.agentclientprotocol.sdk.integration.AcpAgentSettings;
import com.agentclientprotocol.sdk.integration.AcpAgents;
import com.agentclientprotocol.sdk.quarkus.AcpBuildTimeConfig;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import io.quarkus.arc.All;
import io.quarkus.arc.Arc;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Singleton;

/**
 * Assembles the {@link AcpAgentSupport} builder for the application's {@code @AcpAgent}
 * bean ({@link AcpAgents}): the bean instance from the container, the configured timeouts,
 * and the interceptor, argument resolver and return value handler beans, then the Mutiny
 * return types.
 * <p>
 * Handlers are discovered on the user's class (from the build-time index), not on the
 * instance's class, so a container subclass of the bean does not hide them.
 * </p>
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpAgentAssembly {

	private final AcpAgentClass agentClass;

	private final AcpAgentSettings settings;

	private final List<AcpInterceptor> interceptors;

	private final List<ArgumentResolver> argumentResolvers;

	private final List<ReturnValueHandler> returnValueHandlers;

	AcpAgentAssembly(AcpAgentClass agentClass, AcpBuildTimeConfig buildTime, AcpRuntimeConfig runtime,
			@All List<AcpInterceptor> interceptors, @All List<ArgumentResolver> argumentResolvers,
			@All List<ReturnValueHandler> returnValueHandlers) {
		this.agentClass = agentClass;
		this.settings = AcpSettings.agent(buildTime, runtime);
		this.interceptors = interceptors;
		this.argumentResolvers = argumentResolvers;
		List<ReturnValueHandler> handlers = new ArrayList<>(returnValueHandlers);
		handlers.add(new UniReturnValueHandler());
		handlers.add(new MultiReturnValueHandler());
		this.returnValueHandlers = handlers;
	}

	/**
	 * The agent settings, from the configuration.
	 * @return the settings
	 */
	public AcpAgentSettings settings() {
		return settings;
	}

	/**
	 * A builder holding the agent bean and every setting except the transport.
	 * @return a new builder
	 */
	public AcpAgentSupport.Builder builder() {
		return builder(agentClass.type());
	}

	/**
	 * A factory serving one agent per remote connection, all on the one bean, for a
	 * listener transport.
	 * @return the agent factory
	 */
	public AcpAgentFactory factory() {
		return builder().buildFactory();
	}

	private <T> AcpAgentSupport.Builder builder(Class<T> type) {
		T bean = Arc.container().select(type, Any.Literal.INSTANCE).get();
		return AcpAgents.builder(new AgentCandidate<>(type.getName(), type, () -> bean), settings, interceptors,
				argumentResolvers, returnValueHandlers);
	}

}
