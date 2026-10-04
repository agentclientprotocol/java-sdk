/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery.AgentCandidate;
import com.agentclientprotocol.sdk.integration.AcpAgentHost;
import com.agentclientprotocol.sdk.integration.AcpAgentSettings;
import com.agentclientprotocol.sdk.integration.AcpAgentTransports;
import com.agentclientprotocol.sdk.integration.AcpAgents;
import com.agentclientprotocol.sdk.integration.AcpHost;
import com.agentclientprotocol.sdk.integration.AcpListenerHost;
import com.agentclientprotocol.sdk.integration.AcpListeners;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.runtime.graceful.GracefulShutdownCapable;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the application's {@code @AcpAgent} bean, from the application context's start to
 * its close. The agent class is a bean like any other: annotate it {@code @Singleton} (or
 * another bean-defining annotation) as well as {@code @AcpAgent}, and it is found from its
 * compile-time bean definition, with no classpath scan. Exactly one such bean is allowed;
 * with none, as in a client-only application, nothing is started.
 *
 * <p>
 * The transport comes from {@link AcpAgentConfiguration}:
 * <ul>
 * <li>{@code stdio} (default): the agent reads standard input and writes standard output,
 * which therefore must carry nothing else: send logging to standard error and turn off the
 * banner ({@code Micronaut.build(args).banner(false)}). The SDK's threads are daemons, so the
 * runtime holds the JVM open until the transport ends; when the client closes the agent's
 * input and every answer is written, the runtime closes the application context
 * ({@code acp.agent.shutdown-on-transport-end}), and the process exits. An application bean
 * of type {@code AcpAgentTransport} replaces stdio, for tests among others.</li>
 * <li>{@code http} or {@code websocket}: the SDK's Streamable HTTP listener from
 * {@code acp-streamable-http-jetty}, on its own port, serving Streamable HTTP and WebSocket on
 * one path, with one agent per connection invoking the same bean, whose handlers must
 * therefore be thread-safe.</li>
 * </ul>
 *
 * <p>
 * {@code AcpInterceptor}, {@code ArgumentResolver} and {@code ReturnValueHandler} beans are
 * added to the agent, in their bean order. Handler methods may also return a
 * {@code Mono}, a {@code CompletionStage} or a single-value Reactive Streams {@code Publisher},
 * which {@code AcpAgentSupport} waits for.
 *
 * <p>
 * Stopping: closing the context closes the agent gracefully, and with
 * {@code micronaut.lifecycle.graceful-shutdown.enabled} the listener drains through
 * Micronaut's graceful shutdown first. An application without an embedded server has no
 * shutdown hook of Micronaut's, so the runtime registers one that closes the context on
 * SIGTERM.
 */
@Singleton
@Requires(property = AcpAgentConfiguration.PREFIX + ".enabled", notEquals = StringUtils.FALSE)
public final class AcpAgentRuntime implements ApplicationEventListener<StartupEvent>, GracefulShutdownCapable {

	private static final Logger logger = LoggerFactory.getLogger(AcpAgentRuntime.class);

	/** Margin over the agent's own shutdown bound when closing waits for it. */
	private static final Duration CLOSE_MARGIN = Duration.ofSeconds(5);

	private final ApplicationContext context;

	private final AcpAgentConfiguration config;

	private final BeanProvider<AcpAgentTransport> transportBean;

	private final List<AcpInterceptor> interceptors;

	private final List<ArgumentResolver> argumentResolvers;

	private final List<ReturnValueHandler> returnValueHandlers;

	private final Object lock = new Object();

	private @Nullable AcpHost host;

	private @Nullable Thread shutdownHook;

	private boolean started;

	private volatile boolean closing;

	AcpAgentRuntime(ApplicationContext context, AcpAgentConfiguration config,
			BeanProvider<AcpAgentTransport> transportBean, List<AcpInterceptor> interceptors,
			List<ArgumentResolver> argumentResolvers, List<ReturnValueHandler> returnValueHandlers) {
		this.context = context;
		this.config = config;
		this.transportBean = transportBean;
		this.interceptors = interceptors;
		this.argumentResolvers = argumentResolvers;
		this.returnValueHandlers = returnValueHandlers;
	}

	@Override
	public void onApplicationEvent(StartupEvent event) {
		start();
	}

	/**
	 * Starts serving the {@code @AcpAgent} bean, once; the application context's start does
	 * this. Does nothing when the application has no such bean.
	 * @throws IllegalStateException if the application has more than one {@code @AcpAgent}
	 * bean, or asks for HTTP without {@code acp-streamable-http-jetty}
	 */
	public void start() {
		synchronized (lock) {
			if (started || closing) {
				return;
			}
			started = true;
			AgentCandidate<?> agent = findAgent(context);
			if (agent == null) {
				logger.debug("No @AcpAgent bean; no ACP agent is started");
				return;
			}
			AcpAgentSettings settings = config.toSettings();
			logger.info("Serving @AcpAgent bean {} over {}", agent.userClass().getName(), settings.transport());
			AcpHost newHost = createHost(AcpAgents.builder(agent, settings, interceptors, argumentResolvers,
					returnValueHandlers), settings);
			// Before the host serves anything, so a SIGTERM after the first answer is handled.
			registerShutdownHook();
			try {
				newHost.start();
			}
			catch (RuntimeException ex) {
				removeShutdownHook();
				throw ex;
			}
			newHost.port()
				.ifPresent(port -> logger.info("ACP agent listening on port {}, path {}", port, settings.http().path()));
			this.host = newHost;
			if (!settings.servesHttp()) {
				// The SDK's threads are daemons: hold the JVM while the single transport runs.
				newHost.holdJvmUntilTermination();
			}
		}
	}

	/**
	 * The port the HTTP listener is bound to, useful with {@code acp.agent.transport.http.port=0}.
	 * @return the port, or empty when no listener is running
	 */
	public OptionalInt port() {
		AcpHost current = currentHost();
		return (current != null) ? current.port() : OptionalInt.empty();
	}

	/**
	 * Whether an agent is being served.
	 * @return true from a successful start until the close
	 */
	public boolean isRunning() {
		return currentHost() != null && !closing;
	}

	@Override
	public CompletionStage<?> shutdownGracefully() {
		AcpHost current = currentHost();
		if (current == null) {
			return CompletableFuture.completedFuture(null);
		}
		closing = true;
		return current.stopGracefully();
	}

	/** Closes the agent gracefully; the application context's close does this. */
	@PreDestroy
	public void close() {
		AcpHost current;
		synchronized (lock) {
			closing = true;
			current = this.host;
			this.host = null;
		}
		removeShutdownHook();
		if (current != null) {
			logger.info("Stopping the ACP agent");
			current.stop(closeTimeout());
		}
	}

	private @Nullable AcpHost currentHost() {
		synchronized (lock) {
			return host;
		}
	}

	private Duration closeTimeout() {
		Duration shutdownTimeout = config.getTransport().getHttp().getShutdownTimeout();
		Duration bound = (shutdownTimeout != null) ? shutdownTimeout : Duration.ofSeconds(5);
		return bound.plus(CLOSE_MARGIN);
	}

	/**
	 * The single {@code @AcpAgent} bean, found from the annotation metadata the compiler wrote,
	 * or null when there is none.
	 * @throws IllegalStateException when there is more than one
	 */
	static @Nullable AgentCandidate<?> findAgent(ApplicationContext context) {
		List<AgentCandidate<?>> candidates = context.getBeanDefinitions(Qualifiers.byStereotype(AcpAgent.class))
			.stream()
			.<AgentCandidate<?>>map(definition -> candidate(context, definition))
			.toList();
		return AcpAgentDiscovery.requireSingle(candidates, AcpAgentConfiguration.PREFIX + ".enabled").orElse(null);
	}

	private static <T> AgentCandidate<T> candidate(ApplicationContext context, BeanDefinition<T> definition) {
		// The declared bean type, not the instance's class, carries the handler annotations.
		return new AgentCandidate<>(definition.getName(), definition.getBeanType(), () -> context.getBean(definition));
	}

	private AcpHost createHost(AcpAgentSupport.Builder builder, AcpAgentSettings settings) {
		if (!settings.servesHttp()) {
			AcpAgentTransport transport = transportBean.isPresent() ? transportBean.get() : AcpAgentTransports.stdio();
			Runnable onTransportEnd = settings.shutdownOnTransportEnd() ? this::onTransportEnd : () -> {
			};
			return new AcpAgentHost(builder.transport(transport).build(), transport, onTransportEnd);
		}
		if (!AcpListeners.isListenerAvailable()) {
			throw new IllegalStateException(AcpAgentConfiguration.PREFIX + ".transport.type="
					+ settings.transport().value() + " needs com.agentclientprotocol:acp-streamable-http-jetty on the classpath");
		}
		return new AcpListenerHost(AcpListeners.listener(settings, builder.buildFactory()));
	}

	/** Closes the context on SIGTERM when no embedded server's hook of Micronaut's does. */
	private void registerShutdownHook() {
		if (context.findBean(EmbeddedApplication.class).isEmpty()) {
			Thread hook = new Thread(this::onSignal, "acp-agent-shutdown-hook");
			Runtime.getRuntime().addShutdownHook(hook);
			this.shutdownHook = hook;
		}
	}

	private void onTransportEnd() {
		if (closing) {
			return;
		}
		logger.info("ACP agent transport ended; closing the application context");
		// On the host's thread, not the transport's: closing the context closes the transport.
		closeContext();
	}

	/**
	 * SIGTERM: closes the agent itself first, since the context may still be starting (it
	 * counts as running only once every startup listener has returned), then the context.
	 */
	private void onSignal() {
		close();
		closeContext();
	}

	private void closeContext() {
		if (context.isRunning()) {
			context.close();
		}
	}

	private void removeShutdownHook() {
		Thread hook = this.shutdownHook;
		this.shutdownHook = null;
		if (hook == null || hook.equals(Thread.currentThread())) {
			return;
		}
		try {
			Runtime.getRuntime().removeShutdownHook(hook);
		}
		catch (IllegalStateException ex) {
			// The JVM is already shutting down; the hook runs or has run.
		}
	}

}
