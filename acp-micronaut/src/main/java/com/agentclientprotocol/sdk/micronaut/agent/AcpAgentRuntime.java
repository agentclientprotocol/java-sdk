/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.micronaut.TransportType;
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

	/** The listener's class, present only with acp-streamable-http-jetty. */
	private static final String LISTENER_CLASS = "com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport";

	/** Margin over the agent's own shutdown bound when closing waits for it. */
	private static final Duration CLOSE_MARGIN = Duration.ofSeconds(5);

	private final ApplicationContext context;

	private final AcpAgentConfiguration config;

	private final BeanProvider<AcpAgentTransport> transportBean;

	private final List<AcpInterceptor> interceptors;

	private final List<ArgumentResolver> argumentResolvers;

	private final List<ReturnValueHandler> returnValueHandlers;

	private final Object lock = new Object();

	private @Nullable AgentHost host;

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
			BeanDefinition<?> agent = findAgent(context);
			if (agent == null) {
				logger.debug("No @AcpAgent bean; no ACP agent is started");
				return;
			}
			logger.info("Serving @AcpAgent bean {} over {}", agent.getBeanType().getName(),
					config.getTransport().getType());
			AgentHost newHost = createHost(builder(agent));
			// Before the host serves anything, so a SIGTERM after the first answer is handled.
			registerShutdownHook();
			try {
				newHost.start();
			}
			catch (RuntimeException ex) {
				removeShutdownHook();
				throw ex;
			}
			newHost.port().ifPresent(port -> logger.info("ACP agent listening on port {}, path {}", port,
					config.getTransport().getHttp().getPath()));
			this.host = newHost;
			watch(newHost);
		}
	}

	/**
	 * The port the HTTP listener is bound to, useful with {@code acp.agent.transport.http.port=0}.
	 * @return the port, or empty when no listener is running
	 */
	public OptionalInt port() {
		AgentHost current = currentHost();
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
		AgentHost current = currentHost();
		if (current == null) {
			return java.util.concurrent.CompletableFuture.completedFuture(null);
		}
		closing = true;
		return current.closeGracefully().toFuture();
	}

	/** Closes the agent gracefully; the application context's close does this. */
	@PreDestroy
	public void close() {
		AgentHost current;
		synchronized (lock) {
			closing = true;
			current = this.host;
			this.host = null;
		}
		removeShutdownHook();
		if (current != null) {
			logger.info("Stopping the ACP agent");
			current.closeGracefully().block(closeTimeout());
		}
	}

	private @Nullable AgentHost currentHost() {
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
	 * The single {@code @AcpAgent} bean definition, found from the annotation metadata the
	 * compiler wrote, or null when there is none.
	 * @throws IllegalStateException when there is more than one
	 */
	static @Nullable BeanDefinition<?> findAgent(ApplicationContext context) {
		Collection<BeanDefinition<?>> agents = context.getBeanDefinitions(Qualifiers.byStereotype(AcpAgent.class));
		if (agents.size() > 1) {
			throw new IllegalStateException("Found " + agents.size() + " @AcpAgent beans "
					+ agents.stream().map(def -> def.getBeanType().getName()).sorted().collect(Collectors.toList())
					+ ", but an application serves one; set acp.agent.enabled=false or remove all but one");
		}
		return agents.isEmpty() ? null : agents.iterator().next();
	}

	private <T> AcpAgentSupport.Builder builder(BeanDefinition<T> agent) {
		// The declared bean type, not the instance's class, carries the handler annotations.
		AcpAgentSupport.Builder builder = AcpAgentSupport.builder()
			.agent(agent.getBeanType(), () -> context.getBean(agent))
			.requestTimeout(config.getRequestTimeout())
			.cancelGracePeriod(config.getCancelGracePeriod())
			.maxPromptDuration(config.getMaxPromptDuration());
		interceptors.forEach(builder::interceptor);
		argumentResolvers.forEach(builder::argumentResolver);
		returnValueHandlers.forEach(builder::returnValueHandler);
		return builder;
	}

	private AgentHost createHost(AcpAgentSupport.Builder builder) {
		TransportType type = config.getTransport().getType();
		if (type == TransportType.STDIO) {
			AcpAgentTransport transport = transportBean.isPresent() ? transportBean.get()
					: new StdioAcpAgentTransport();
			return new SingleTransportHost(builder, transport);
		}
		if (!isListenerPresent()) {
			throw new IllegalStateException("acp.agent.transport.type=" + type.name().toLowerCase(java.util.Locale.ROOT)
					+ " needs com.agentclientprotocol:acp-streamable-http-jetty on the classpath");
		}
		return new HttpListenerHost(builder.buildFactory(), config.getTransport().getHttp());
	}

	private static boolean isListenerPresent() {
		try {
			Class.forName(LISTENER_CLASS, false, AcpAgentRuntime.class.getClassLoader());
			return true;
		}
		catch (ClassNotFoundException ex) {
			return false;
		}
	}

	/** Closes the context on SIGTERM when no embedded server's hook of Micronaut's does. */
	private void registerShutdownHook() {
		if (context.findBean(EmbeddedApplication.class).isEmpty()) {
			Thread hook = new Thread(this::onSignal, "acp-agent-shutdown-hook");
			Runtime.getRuntime().addShutdownHook(hook);
			this.shutdownHook = hook;
		}
	}

	/**
	 * Holds the JVM open while a single transport runs (the SDK's threads are daemons) and
	 * closes the context when that transport ends by itself.
	 */
	private void watch(AgentHost started) {
		if (!started.endsWithItsClient()) {
			return;
		}
		CountDownLatch ended = new CountDownLatch(1);
		started.awaitTermination().subscribe(null, error -> ended.countDown(), ended::countDown);
		Thread keepAlive = new Thread(() -> {
			try {
				ended.await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			onTransportEnd();
		}, "acp-agent-await");
		keepAlive.setDaemon(false);
		keepAlive.start();
	}

	private void onTransportEnd() {
		if (closing || !config.isShutdownOnTransportEnd()) {
			return;
		}
		logger.info("ACP agent transport ended; closing the application context");
		// On this thread, not the transport's: closing the context closes the transport.
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
