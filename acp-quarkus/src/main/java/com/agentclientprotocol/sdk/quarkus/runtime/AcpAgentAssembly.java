/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.List;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
import io.quarkus.arc.All;
import io.quarkus.arc.Arc;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Singleton;

/**
 * Assembles the {@link AcpAgentSupport} builder for the application's {@code @AcpAgent}
 * bean: the bean instance from the container, the configured timeouts, and the
 * interceptor, argument resolver and return value handler beans, then the Mutiny return
 * types.
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

	private final AcpRuntimeConfig config;

	private final List<AcpInterceptor> interceptors;

	private final List<ArgumentResolver> argumentResolvers;

	private final List<ReturnValueHandler> returnValueHandlers;

	AcpAgentAssembly(AcpAgentClass agentClass, AcpRuntimeConfig config, @All List<AcpInterceptor> interceptors,
			@All List<ArgumentResolver> argumentResolvers, @All List<ReturnValueHandler> returnValueHandlers) {
		this.agentClass = agentClass;
		this.config = config;
		this.interceptors = interceptors;
		this.argumentResolvers = argumentResolvers;
		this.returnValueHandlers = returnValueHandlers;
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
		AcpRuntimeConfig.Agent agent = config.agent();
		AcpAgentSupport.Builder builder = AcpAgentSupport.builder().agent(type, () -> bean);
		agent.requestTimeout().ifPresent(builder::requestTimeout);
		agent.cancelGracePeriod().ifPresent(builder::cancelGracePeriod);
		agent.maxPromptDuration().ifPresent(builder::maxPromptDuration);
		interceptors.forEach(builder::interceptor);
		argumentResolvers.forEach(builder::argumentResolver);
		returnValueHandlers.forEach(builder::returnValueHandler);
		builder.returnValueHandler(new UniReturnValueHandler());
		builder.returnValueHandler(new MultiReturnValueHandler());
		return builder;
	}

}
