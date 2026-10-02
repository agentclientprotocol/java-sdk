/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.handler.DirectResponseHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ExtensionResultHandler;
import com.agentclientprotocol.sdk.agent.support.handler.MonoHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandlerComposite;
import com.agentclientprotocol.sdk.agent.support.handler.StringToPromptResponseHandler;
import com.agentclientprotocol.sdk.agent.support.handler.VoidHandler;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.interceptor.InterceptorChain;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolverComposite;
import com.agentclientprotocol.sdk.agent.support.resolver.CancelNotificationResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.CapabilitiesResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.CloseSessionRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.DeleteSessionRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.DisableProviderRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.ExtensionParamsResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.ForkSessionRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.InitializeRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.ListProvidersRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.ListSessionsRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.LoadSessionRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.LogoutRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.NewSessionRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.PromptContextResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.PromptRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.ResumeSessionRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.SessionIdResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.SetProviderRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.SetSessionConfigOptionRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.SetSessionModeRequestResolver;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.CloseSession;
import com.agentclientprotocol.sdk.annotation.DeleteSession;
import com.agentclientprotocol.sdk.annotation.DisableProvider;
import com.agentclientprotocol.sdk.annotation.ExtNotification;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import com.agentclientprotocol.sdk.annotation.ForkSession;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.ListProviders;
import com.agentclientprotocol.sdk.annotation.ListSessions;
import com.agentclientprotocol.sdk.annotation.LoadSession;
import com.agentclientprotocol.sdk.annotation.Logout;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.ResumeSession;
import com.agentclientprotocol.sdk.annotation.SetProvider;
import com.agentclientprotocol.sdk.annotation.SetSessionConfigOption;
import com.agentclientprotocol.sdk.annotation.SetSessionMode;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.ExtensionMethods;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.PromptTimeouts;
import com.agentclientprotocol.sdk.util.Assert;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstrap class for annotation-based ACP agents.
 *
 * <p>This class provides a fluent builder API to configure and run
 * annotation-based agents without requiring any external framework.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AcpAgent
 * class MyAgent {
 *     @Initialize
 *     InitializeResponse init(InitializeRequest req) {
 *         return InitializeResponse.ok();
 *     }
 *
 *     @Prompt
 *     PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
 *         ctx.sendMessage("Hello!");
 *         return PromptResponse.endTurn();
 *     }
 * }
 *
 * // Bootstrap
 * AcpAgentSupport.create(new MyAgent())
 *     .transport(StdioAcpAgentTransport.create())
 *     .run();
 * }</pre>
 *
 * <p>A listener transport accepts many connections and needs one agent per connection:
 * {@link Builder#buildFactory()} gives it an {@link AcpAgentFactory} instead.
 * <pre>{@code
 * AcpAgentFactory factory = AcpAgentSupport.create(new MyAgent()).buildFactory();
 * new StreamableHttpAcpAgentTransport(8080, jsonMapper, factory).start().block();
 * }</pre>
 *
 * <p><b>One handler bean, shared.</b> The annotated object is the application's bean, like a
 * Spring controller: the builder discovers its handler methods once, and every agent the
 * builder builds, every connection a factory serves included, invokes the same instance.
 * Its handler methods are therefore called concurrently, from different connections and
 * from different sessions of one connection, and must be thread-safe; keep per-connection
 * or per-session state keyed by session id, not in plain fields. The same holds for
 * interceptors and custom resolvers and return value handlers.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class AcpAgentSupport {

	private static final Logger log = LoggerFactory.getLogger(AcpAgentSupport.class);

	/** The handler annotations and the ACP method each one marks a handler for. */
	private static final Map<Class<? extends Annotation>, String> HANDLER_ANNOTATIONS = handlerAnnotations();

	private final Map<String, AcpHandlerMethod> handlers;

	private final ArgumentResolverComposite argumentResolvers;

	private final ReturnValueHandlerComposite returnValueHandlers;

	private final List<AcpInterceptor> interceptors;

	private final AcpSyncAgent agent;

	private AcpAgentSupport(Definition definition, AcpAgentTransport transport) {
		this.handlers = definition.handlers();
		this.argumentResolvers = definition.argumentResolvers();
		this.returnValueHandlers = definition.returnValueHandlers();
		this.interceptors = definition.interceptors();

		// Build the underlying sync agent
		var agentBuilder = AcpAgent.sync(transport)
				.requestTimeout(definition.requestTimeout())
				.cancelGracePeriod(definition.cancelGracePeriod())
				.maxPromptDuration(definition.maxPromptDuration());

		// Wire discovered handlers to the agent builder
		wireHandlers(agentBuilder);

		this.agent = agentBuilder.build();
	}

	/**
	 * Create a new builder for an agent instance.
	 * @param agentInstance the annotated agent instance
	 * @return a new builder
	 */
	public static Builder create(Object agentInstance) {
		return new Builder().agent(agentInstance);
	}

	/**
	 * Create a new builder for an agent class (requires no-arg constructor). The class is
	 * instantiated once, here, and that instance serves every request.
	 * @param agentClass the annotated agent class
	 * @return a new builder
	 */
	public static Builder create(Class<?> agentClass) {
		return new Builder().agent(agentClass);
	}

	/**
	 * Create a new empty builder.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Start the agent (non-blocking).
	 */
	public void start() {
		log.info("Starting annotation-based ACP agent");
		agent.start();
	}

	/**
	 * Run the agent (blocking until close).
	 */
	public void run() {
		start();
		agent.await();
	}

	/**
	 * Close the agent gracefully.
	 */
	public void close() {
		log.info("Closing annotation-based ACP agent");
		agent.closeGracefully();
	}

	/**
	 * Get the underlying sync agent.
	 * @return the sync agent
	 */
	public AcpSyncAgent getAgent() {
		return agent;
	}

	/**
	 * Binds each discovered handler method to the agent builder, one ACP method per line.
	 * Initialize and session/new have defaults, so an agent with only a prompt handler can
	 * still be initialized and open sessions.
	 */
	private void wireHandlers(AcpAgent.SyncAgentBuilder agent) {
		bind(AcpSchema.METHOD_INITIALIZE,
				handler -> agent.initializeHandler(req -> respond(handler, InitializeResponse.class, req, null)),
				() -> agent.initializeHandler(req -> InitializeResponse.ok()));
		bind(AcpSchema.METHOD_SESSION_NEW,
				handler -> agent.newSessionHandler(req -> respond(handler, NewSessionResponse.class, req, null)),
				() -> agent.newSessionHandler(req -> new NewSessionResponse(UUID.randomUUID().toString(), null, null)));
		bind(AcpSchema.METHOD_LOGOUT,
				handler -> agent.logoutHandler(req -> respond(handler, AcpSchema.LogoutResponse.class, req, null)));
		bind(AcpSchema.METHOD_SESSION_LOAD, handler -> agent.loadSessionHandler(
				req -> respond(handler, AcpSchema.LoadSessionResponse.class, req, req.sessionId())));
		bind(AcpSchema.METHOD_SESSION_PROMPT, handler -> agent.promptHandler((req, context) -> respond(handler,
				AcpSchema.PromptResponse.class, req, req.sessionId(), context, context.getClientCapabilities())));
		bind(AcpSchema.METHOD_SESSION_SET_MODE, handler -> agent.setSessionModeHandler(
				req -> respond(handler, AcpSchema.SetSessionModeResponse.class, req, req.sessionId())));
		bind(AcpSchema.METHOD_SESSION_LIST, handler -> agent
			.listSessionsHandler(req -> respond(handler, AcpSchema.ListSessionsResponse.class, req, null)));
		bind(AcpSchema.METHOD_SESSION_CLOSE, handler -> agent.closeSessionHandler(
				req -> respond(handler, AcpSchema.CloseSessionResponse.class, req, req.sessionId())));
		bind(AcpSchema.METHOD_SESSION_DELETE, handler -> agent.deleteSessionHandler(
				req -> respond(handler, AcpSchema.DeleteSessionResponse.class, req, req.sessionId())));
		bind(AcpSchema.METHOD_SESSION_RESUME, handler -> agent.resumeSessionHandler(
				req -> respond(handler, AcpSchema.ResumeSessionResponse.class, req, req.sessionId())));
		bind(AcpSchema.METHOD_SESSION_FORK, handler -> agent.forkSessionHandler(
				req -> respond(handler, AcpSchema.ForkSessionResponse.class, req, req.sessionId())));
		bind(AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION, handler -> agent.setSessionConfigOptionHandler(
				req -> respond(handler, AcpSchema.SetSessionConfigOptionResponse.class, req, req.sessionId())));
		bind(AcpSchema.METHOD_PROVIDERS_LIST, handler -> agent
			.listProvidersHandler(req -> respond(handler, AcpSchema.ListProvidersResponse.class, req, null)));
		bind(AcpSchema.METHOD_PROVIDERS_SET, handler -> agent
			.setProviderHandler(req -> respond(handler, AcpSchema.SetProviderResponse.class, req, null)));
		bind(AcpSchema.METHOD_PROVIDERS_DISABLE, handler -> agent
			.disableProviderHandler(req -> respond(handler, AcpSchema.DisableProviderResponse.class, req, null)));
		bind(AcpSchema.METHOD_SESSION_CANCEL, handler -> agent.cancelHandler(
				notification -> invoke(handler, notification, notification.sessionId(), null, null)));
		handlers.values()
			.stream()
			.filter(handler -> ExtensionMethods.isExtension(handler.getAcpMethod()))
			.forEach(handler -> bindExtension(agent, handler));
	}

	/**
	 * Binds an {@link ExtRequest} or {@link ExtNotification} method, its params read as its
	 * parameter's type ({@code Object}, the raw JSON value, when it takes none).
	 */
	private void bindExtension(AcpAgent.SyncAgentBuilder agent, AcpHandlerMethod handler) {
		Method method = handler.getMethod();
		TypeRef<?> paramsType = TypeRef
			.of(method.getParameterCount() == 1 ? method.getGenericParameterTypes()[0] : Object.class);
		if (method.isAnnotationPresent(ExtNotification.class)) {
			bindExtNotification(agent, handler, paramsType);
		}
		else {
			bindExtRequest(agent, handler, paramsType);
		}
	}

	private <T> void bindExtRequest(AcpAgent.SyncAgentBuilder agent, AcpHandlerMethod handler,
			TypeRef<T> paramsType) {
		agent.extRequestHandler(handler.getAcpMethod(), paramsType,
				params -> respond(handler, Object.class, params, null));
	}

	private <T> void bindExtNotification(AcpAgent.SyncAgentBuilder agent, AcpHandlerMethod handler,
			TypeRef<T> paramsType) {
		agent.extNotificationHandler(handler.getAcpMethod(), paramsType,
				params -> invoke(handler, params, null, null, null));
	}

	/** Binds the handler method discovered for an ACP method, if there is one. */
	private void bind(String acpMethod, Consumer<AcpHandlerMethod> binding) {
		bind(acpMethod, binding, () -> {
		});
	}

	/** Binds the handler method discovered for an ACP method, or the default when there is none. */
	private void bind(String acpMethod, Consumer<AcpHandlerMethod> binding, Runnable defaultBinding) {
		AcpHandlerMethod handler = handlers.get(acpMethod);
		if (handler != null) {
			binding.accept(handler);
		}
		else {
			defaultBinding.run();
		}
	}

	/** {@link #respond(AcpHandlerMethod, Class, Object, String, SyncPromptContext, NegotiatedCapabilities)} for a method other than session/prompt. */
	private <T> T respond(AcpHandlerMethod handler, Class<T> responseType, Object request,
			@Nullable String sessionId) {
		return respond(handler, responseType, request, sessionId, null, null);
	}

	/**
	 * Invokes the handler for a request, whose JSON-RPC response must carry a result. A
	 * handler that produces none (it returned null, returned void for a method other than
	 * session/prompt, or an interceptor vetoed the call) is answered with an error: an
	 * empty handler result would otherwise leave the request without any response. A result
	 * that is not the method's response type is answered with an error naming both types.
	 */
	private <T> T respond(AcpHandlerMethod handler, Class<T> responseType, Object request,
			@Nullable String sessionId, @Nullable SyncPromptContext syncContext,
			@Nullable NegotiatedCapabilities capabilities) {
		Object result = invoke(handler, request, sessionId, syncContext, capabilities);
		if (result == null) {
			throw new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR, "The " + handler.getAcpMethod()
					+ " handler produced no response (it returned nothing, or an interceptor vetoed the call)");
		}
		if (!responseType.isInstance(result)) {
			throw new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR, "The " + handler.getAcpMethod()
					+ " handler produced a " + result.getClass().getName() + ", not a " + responseType.getName());
		}
		return responseType.cast(result);
	}

	/**
	 * Runs the interceptor chain, argument resolution, the handler and its return value
	 * handling.
	 * @return the handler's result, or null when it produced none or an interceptor vetoed
	 * the call
	 */
	private @Nullable Object invoke(AcpHandlerMethod handler, Object request, @Nullable String sessionId,
			@Nullable SyncPromptContext syncContext, @Nullable NegotiatedCapabilities capabilities) {

		AcpInvocationContext context = AcpInvocationContext.builder()
				.acpMethod(handler.getAcpMethod())
				.request(request)
				.sessionId(sessionId)
				.syncPromptContext(syncContext)
				.capabilities(capabilities)
				.build();

		InterceptorChain chain = new InterceptorChain(interceptors);

		try {
			// Pre-invoke
			if (!chain.applyPreInvoke(context)) {
				return null;
			}

			// Resolve arguments
			@Nullable Object[] args = resolveArguments(handler, context);

			// Invoke
			Object result = handler.invoke(args);

			// Post-invoke
			result = chain.applyPostInvoke(context, result);

			// Handle return value
			return returnValueHandlers.handleReturnValue(result, handler.getReturnType(), context);

		}
		catch (Exception e) {
			// On-error
			Object replacement = chain.applyOnError(context, e);
			if (replacement != null) {
				return replacement;
			}
			if (e instanceof RuntimeException re) {
				throw re;
			}
			throw new RuntimeException(e);
		}
		finally {
			chain.triggerAfterCompletion(context, null);
		}
	}

	private @Nullable Object[] resolveArguments(AcpHandlerMethod handler, AcpInvocationContext context) {
		AcpMethodParameter[] params = handler.getParameters();
		@Nullable Object[] args = new Object[params.length];
		for (int i = 0; i < params.length; i++) {
			args[i] = argumentResolvers.resolveArgument(params[i], context);
		}
		return args;
	}

	private static Map<Class<? extends Annotation>, String> handlerAnnotations() {
		Map<Class<? extends Annotation>, String> annotations = new LinkedHashMap<>();
		annotations.put(Initialize.class, AcpSchema.METHOD_INITIALIZE);
		annotations.put(Logout.class, AcpSchema.METHOD_LOGOUT);
		annotations.put(NewSession.class, AcpSchema.METHOD_SESSION_NEW);
		annotations.put(LoadSession.class, AcpSchema.METHOD_SESSION_LOAD);
		annotations.put(Prompt.class, AcpSchema.METHOD_SESSION_PROMPT);
		annotations.put(SetSessionMode.class, AcpSchema.METHOD_SESSION_SET_MODE);
		annotations.put(ListSessions.class, AcpSchema.METHOD_SESSION_LIST);
		annotations.put(CloseSession.class, AcpSchema.METHOD_SESSION_CLOSE);
		annotations.put(DeleteSession.class, AcpSchema.METHOD_SESSION_DELETE);
		annotations.put(ResumeSession.class, AcpSchema.METHOD_SESSION_RESUME);
		annotations.put(ForkSession.class, AcpSchema.METHOD_SESSION_FORK);
		annotations.put(SetSessionConfigOption.class, AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION);
		annotations.put(ListProviders.class, AcpSchema.METHOD_PROVIDERS_LIST);
		annotations.put(SetProvider.class, AcpSchema.METHOD_PROVIDERS_SET);
		annotations.put(DisableProvider.class, AcpSchema.METHOD_PROVIDERS_DISABLE);
		annotations.put(Cancel.class, AcpSchema.METHOD_SESSION_CANCEL);
		return Collections.unmodifiableMap(annotations);
	}

	/**
	 * What a builder held when it built: the agents it builds share it, and a later change
	 * to the builder does not reach them. The composites are complete (custom entries first,
	 * then the defaults) and never change after construction, so connections share them.
	 */
	private record Definition(Map<String, AcpHandlerMethod> handlers, ArgumentResolverComposite argumentResolvers,
			ReturnValueHandlerComposite returnValueHandlers, List<AcpInterceptor> interceptors,
			Duration requestTimeout, Duration cancelGracePeriod, Duration maxPromptDuration) {
	}

	// ========== BUILDER ==========

	/**
	 * Builder for AcpAgentSupport. A builder can be built any number of times: each
	 * {@link #build()} and {@link #buildFactory()} composes the default argument resolvers
	 * and return value handlers after the custom ones without changing the builder, and
	 * what is built is not affected by later changes to the builder. Every agent built from
	 * one builder invokes the same annotated handler instance (see {@link AcpAgentSupport}).
	 */
	public static class Builder {

		private final Map<String, AcpHandlerMethod> handlers = new HashMap<>();

		private final List<ArgumentResolver> customArgumentResolvers = new ArrayList<>();

		private final List<ReturnValueHandler> customReturnValueHandlers = new ArrayList<>();

		private final List<AcpInterceptor> interceptors = new ArrayList<>();

		private @Nullable AcpAgentTransport transport;

		private Duration requestTimeout = Duration.ofSeconds(30);

		private Duration cancelGracePeriod = PromptTimeouts.DEFAULT_CANCEL_GRACE_PERIOD;

		private Duration maxPromptDuration = Duration.ZERO;

		/**
		 * Register an agent instance.
		 * @param agentInstance the annotated agent instance
		 * @return this builder
		 */
		public Builder agent(Object agentInstance) {
			discoverHandlers(agentInstance.getClass(), () -> agentInstance);
			return this;
		}

		/**
		 * Register an agent class (must have no-arg constructor). The class is
		 * instantiated once, here, and that instance serves every request.
		 * @param agentClass the annotated agent class
		 * @return this builder
		 * @throws IllegalArgumentException if the class cannot be instantiated
		 */
		public Builder agent(Class<?> agentClass) {
			discoverHandlers(agentClass, () -> {
				try {
					return agentClass.getDeclaredConstructor().newInstance();
				}
				catch (ReflectiveOperationException e) {
					throw new IllegalArgumentException("Cannot instantiate " + agentClass.getName(), e);
				}
			});
			return this;
		}

		/**
		 * Register an agent class with factory. The factory is called once, here, and the
		 * instance it returns serves every request.
		 * @param agentClass the annotated agent class
		 * @param factory supplier of the agent instance
		 * @param <T> the agent type
		 * @return this builder
		 */
		public <T> Builder agent(Class<T> agentClass, Supplier<T> factory) {
			discoverHandlers(agentClass, factory::get);
			return this;
		}

		/**
		 * Set the transport.
		 * @param transport the agent transport
		 * @return this builder
		 */
		public Builder transport(AcpAgentTransport transport) {
			this.transport = transport;
			return this;
		}

		/**
		 * Set the request timeout.
		 * @param timeout the timeout duration
		 * @return this builder
		 */
		public Builder requestTimeout(Duration timeout) {
			this.requestTimeout = timeout;
			return this;
		}

		/**
		 * Set how long a {@code @Prompt} method has to return after {@code session/cancel}
		 * before the agent answers the prompt with stop reason {@code cancelled} itself and
		 * interrupts the method's thread. Default 60 seconds; {@link Duration#ZERO} for
		 * none. See {@code AcpAgent.SyncAgentBuilder#cancelGracePeriod}.
		 * @param gracePeriod the grace period; zero for none, not negative
		 * @return this builder
		 */
		public Builder cancelGracePeriod(Duration gracePeriod) {
			this.cancelGracePeriod = gracePeriod;
			return this;
		}

		/**
		 * Set how long a prompt may run before the agent answers it with error
		 * {@code -32800} (request cancelled). Default none ({@link Duration#ZERO}). See
		 * {@code AcpAgent.SyncAgentBuilder#maxPromptDuration}.
		 * @param maxDuration the maximum prompt duration; zero for none, not negative
		 * @return this builder
		 */
		public Builder maxPromptDuration(Duration maxDuration) {
			this.maxPromptDuration = maxDuration;
			return this;
		}

		/**
		 * Add an interceptor.
		 * @param interceptor the interceptor
		 * @return this builder
		 */
		public Builder interceptor(AcpInterceptor interceptor) {
			this.interceptors.add(interceptor);
			return this;
		}

		/**
		 * Add a custom argument resolver.
		 * @param resolver the resolver
		 * @return this builder
		 */
		public Builder argumentResolver(ArgumentResolver resolver) {
			Assert.notNull(resolver, "The resolver must not be null");
			this.customArgumentResolvers.add(resolver);
			return this;
		}

		/**
		 * Add a custom return value handler.
		 * @param handler the handler
		 * @return this builder
		 */
		public Builder returnValueHandler(ReturnValueHandler handler) {
			Assert.notNull(handler, "The handler must not be null");
			this.customReturnValueHandlers.add(handler);
			return this;
		}

		/**
		 * Build the AcpAgentSupport instance on the configured transport. May be called
		 * again, with the same or another transport, for another agent.
		 * @return the configured instance
		 * @throws IllegalStateException if no transport is configured
		 */
		public AcpAgentSupport build() {
			AcpAgentTransport transport = this.transport;
			if (transport == null) {
				throw new IllegalStateException("Transport must be configured");
			}
			return new AcpAgentSupport(definition(), transport);
		}

		/**
		 * Build a factory for a listener transport, such as
		 * {@code StreamableHttpAcpAgentTransport} or {@code StreamableHttpAcpServlet}, that
		 * creates a fresh agent for each connection it accepts. Every agent invokes the same
		 * annotated handler instance, concurrently across connections, so its handlers must
		 * be thread-safe (see {@link AcpAgentSupport}). The factory captures the builder as
		 * it is now; a {@link #transport} set on the builder is not used, as the listener
		 * supplies one per connection.
		 * @return a factory creating one agent per connection
		 */
		public AcpAgentFactory buildFactory() {
			Definition definition = definition();
			return AcpAgentFactory.sync(connection -> new AcpAgentSupport(definition, connection).getAgent());
		}

		/** The builder's configuration now, with the defaults after the custom entries. */
		private Definition definition() {
			ArgumentResolverComposite argumentResolvers = new ArgumentResolverComposite()
				.addResolvers(customArgumentResolvers)
				.addResolvers(defaultResolvers());
			ReturnValueHandlerComposite returnValueHandlers = new ReturnValueHandlerComposite()
				.addHandlers(customReturnValueHandlers)
				.addHandlers(defaultReturnValueHandlers());
			return new Definition(Map.copyOf(handlers), argumentResolvers, returnValueHandlers,
					List.copyOf(interceptors), requestTimeout, cancelGracePeriod, maxPromptDuration);
		}

		private void discoverHandlers(Class<?> agentClass, Supplier<Object> instanceFactory) {
			if (!agentClass.isAnnotationPresent(com.agentclientprotocol.sdk.annotation.AcpAgent.class)) {
				throw new IllegalArgumentException("Class must be annotated with @AcpAgent: " + agentClass.getName());
			}
			// One instance for every handler and request: handlers share the agent's state
			Object agentInstance = instanceFactory.get();
			for (Method method : agentClass.getDeclaredMethods()) {
				HANDLER_ANNOTATIONS.forEach((annotation, acpMethod) -> {
					if (method.isAnnotationPresent(annotation)) {
						handlers.put(acpMethod, new AcpHandlerMethod(agentInstance, method, acpMethod));
						log.debug("Discovered @{} handler: {}", annotation.getSimpleName(), method.getName());
					}
				});
				String extensionMethod = extensionMethod(method);
				if (extensionMethod != null) {
					handlers.put(extensionMethod, new AcpHandlerMethod(agentInstance, method, extensionMethod));
					log.debug("Discovered extension handler for {}: {}", extensionMethod, method.getName());
				}
			}
		}

		/**
		 * The extension method an {@link ExtRequest} or {@link ExtNotification} method
		 * handles, or null when it has neither annotation.
		 * @throws IllegalArgumentException if the name does not start with {@code _}, or the
		 * method takes more than one parameter
		 */
		private static @Nullable String extensionMethod(Method method) {
			ExtRequest request = method.getAnnotation(ExtRequest.class);
			ExtNotification notification = method.getAnnotation(ExtNotification.class);
			String name = (request != null) ? request.value() : (notification != null) ? notification.value() : null;
			if (name == null) {
				return null;
			}
			ExtensionMethods.requireExtension(name);
			if (method.getParameterCount() > 1) {
				throw new IllegalArgumentException("Extension handler " + method.getName()
						+ " must take at most one parameter, which receives the params of " + name);
			}
			return name;
		}

		private static List<ArgumentResolver> defaultResolvers() {
			// Built-in resolvers (order matters - first match wins), after the custom ones
			return List.of(
					new ExtensionParamsResolver(),
					new InitializeRequestResolver(),
					new LogoutRequestResolver(),
					new NewSessionRequestResolver(),
					new LoadSessionRequestResolver(),
					new PromptRequestResolver(),
					new SetSessionModeRequestResolver(),
					new ListSessionsRequestResolver(),
					new CloseSessionRequestResolver(),
					new DeleteSessionRequestResolver(),
					new ResumeSessionRequestResolver(),
					new ForkSessionRequestResolver(),
					new SetSessionConfigOptionRequestResolver(),
					new ListProvidersRequestResolver(),
					new SetProviderRequestResolver(),
					new DisableProviderRequestResolver(),
					new CancelNotificationResolver(),
					new PromptContextResolver(),
					new SessionIdResolver(),
					new CapabilitiesResolver());
		}

		private static List<ReturnValueHandler> defaultReturnValueHandlers() {
			// Built-in handlers (order matters - first match wins), after the custom ones.
			// Mono is supported as Reactor is available (acp-core depends on it); extension
			// results are any value, so their handler is last, after Mono and void.
			return List.of(new DirectResponseHandler(), new StringToPromptResponseHandler(), new VoidHandler(),
					new MonoHandler(), new ExtensionResultHandler());
		}

	}

}
