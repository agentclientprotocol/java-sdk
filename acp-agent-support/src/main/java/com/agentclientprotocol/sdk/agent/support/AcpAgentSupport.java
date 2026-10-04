/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.handler.AsyncValueHandler;
import com.agentclientprotocol.sdk.agent.support.handler.DirectResponseHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ExtensionResultHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandlerComposite;
import com.agentclientprotocol.sdk.agent.support.handler.StringToPromptResponseHandler;
import com.agentclientprotocol.sdk.agent.support.handler.VoidHandler;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.interceptor.InterceptorChain;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.agent.support.resolver.AgentResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolverComposite;
import com.agentclientprotocol.sdk.agent.support.resolver.AuthenticateRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.CancelNotificationResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.CapabilitiesResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.CloseSessionRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.ConfigOptionResolver;
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
import com.agentclientprotocol.sdk.annotation.Authenticate;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.ConfigId;
import com.agentclientprotocol.sdk.annotation.ConfigValue;
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
import com.agentclientprotocol.sdk.annotation.SessionId;
import com.agentclientprotocol.sdk.annotation.SetSessionMode;
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
 * @AcpAgent(name = "my-agent", version = "1.0")
 * class MyAgent {
 *     @LoadSession
 *     LoadSessionResponse load(LoadSessionRequest req) {
 *         return new LoadSessionResponse(null);
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
 *     .transport(new StdioAcpAgentTransport())
 *     .run();
 * }</pre>
 *
 * <p><b>Advertising.</b> The agent answers {@code initialize} with what its annotations
 * declare, with no {@code @Initialize} method needed: each handler annotation advertises the
 * capability its ACP method needs (above, {@code loadSession}), the {@code @AcpAgent}
 * attributes give {@code agentInfo}, {@code authMethods} and the MCP transports, and the
 * {@code @Prompt} attributes the prompt content accepted. An {@code @Initialize} method's
 * response is laid over the derived one; see {@code Initialize} for the merge rule.
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
 * interceptors and custom resolvers and return value handlers. For the same reason a bean
 * cannot hold "its" agent: a handler that needs the connection's agent (to push a session
 * update outside a prompt) or its negotiated capabilities takes an {@code AcpSyncAgent},
 * {@code AcpAsyncAgent} or {@code NegotiatedCapabilities} parameter, resolved per call for the
 * connection the request arrived on.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class AcpAgentSupport implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(AcpAgentSupport.class);

	/** The handler annotations and the ACP method each one marks a handler for. */
	private static final Map<Class<? extends Annotation>, String> HANDLER_ANNOTATIONS = handlerAnnotations();

	private final Map<String, AcpHandlerMethod> handlers;

	private final ArgumentResolverComposite argumentResolvers;

	private final ReturnValueHandlerComposite returnValueHandlers;

	private final List<AcpInterceptor> interceptors;

	private final AgentAdvertisement advertisement;

	private final AcpSyncAgent agent;

	private AcpAgentSupport(Definition definition, AcpAgentTransport transport) {
		this.handlers = definition.handlers();
		this.advertisement = definition.advertisement();
		this.argumentResolvers = definition.argumentResolvers();
		this.returnValueHandlers = definition.returnValueHandlers();
		this.interceptors = definition.interceptors();

		// Build the underlying sync agent
		var agentBuilder = AcpAgent.sync(transport)
				.cancelGracePeriod(definition.cancelGracePeriod())
				.maxPromptDuration(definition.maxPromptDuration());
		ExecutorService handlerExecutor = definition.handlerExecutor();
		if (handlerExecutor != null) {
			agentBuilder.handlerExecutor(handlerExecutor);
		}
		// Unset, the agent builder's default applies: the SDK has one default request timeout.
		Duration requestTimeout = definition.requestTimeout();
		if (requestTimeout != null) {
			agentBuilder.requestTimeout(requestTimeout);
		}

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
		agent.awaitTermination();
	}

	/**
	 * Closes the agent as {@link AcpSyncAgent#close()} does: gracefully, waiting at most 10
	 * seconds, then at once if that failed or took longer. Try-with-resources calls it.
	 */
	@Override
	public void close() {
		log.info("Closing annotation-based ACP agent");
		agent.close();
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
	 * still be initialized and open sessions. Initialize answers with the response derived
	 * from the annotations ({@link AgentAdvertisement}), with an {@code @Initialize} method's
	 * response laid over it when there is one.
	 */
	private void wireHandlers(AcpAgent.SyncAgentBuilder agent) {
		bind(AcpSchema.METHOD_INITIALIZE,
				handler -> agent.initializeHandler(req -> AgentAdvertisement.merge(advertisement.derive(req),
						respond(handler, InitializeResponse.class, req, null))),
				() -> agent.initializeHandler(req -> deriveInitialize(req)));
		bind(AcpSchema.METHOD_SESSION_NEW,
				handler -> agent.newSessionHandler(req -> respond(handler, NewSessionResponse.class, req, null)),
				() -> agent.newSessionHandler(req -> new NewSessionResponse(UUID.randomUUID().toString(), null, null)));
		bind(AcpSchema.METHOD_AUTHENTICATE, handler -> agent
			.authenticateHandler(req -> respond(handler, AcpSchema.AuthenticateResponse.class, req, null)));
		bind(AcpSchema.METHOD_LOGOUT,
				handler -> agent.logoutHandler(req -> respond(handler, AcpSchema.LogoutResponse.class, req, null)));
		bind(AcpSchema.METHOD_SESSION_LOAD, handler -> agent.loadSessionHandler(
				req -> respond(handler, AcpSchema.LoadSessionResponse.class, req, req.sessionId())));
		bind(AcpSchema.METHOD_SESSION_PROMPT, handler -> agent.promptHandler(
				(req, context) -> respond(handler, AcpSchema.PromptResponse.class, req, req.sessionId(), context)));
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
				notification -> invoke(handler, notification, notification.sessionId(), null)));
		handlers.values()
			.stream()
			.filter(handler -> ExtensionMethods.isExtension(handler.getAcpMethod()))
			.forEach(handler -> bindExtension(agent, handler));
	}

	/**
	 * Binds an {@link ExtRequest} or {@link ExtNotification} method, its params read as its
	 * params parameter's type ({@code Object}, the raw JSON value, when it takes none).
	 */
	private void bindExtension(AcpAgent.SyncAgentBuilder agent, AcpHandlerMethod handler) {
		Method method = handler.getMethod();
		Type params = Object.class;
		for (int i = 0; i < method.getParameterCount(); i++) {
			if (!ExtensionParamsResolver.isConnectionType(method.getParameterTypes()[i])) {
				params = method.getGenericParameterTypes()[i];
			}
		}
		TypeRef<?> paramsType = TypeRef.of(params);
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
				params -> invoke(handler, params, null, null));
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

	/** {@link #respond(AcpHandlerMethod, Class, Object, String, SyncPromptContext)} for a method other than session/prompt. */
	private <T> T respond(AcpHandlerMethod handler, Class<T> responseType, Object request,
			@Nullable String sessionId) {
		return respond(handler, responseType, request, sessionId, null);
	}

	/**
	 * Invokes the handler for a request, whose JSON-RPC response must carry a result. A
	 * handler that produces none (it returned null, returned void for a method other than
	 * session/prompt, or an interceptor vetoed the call) is answered with an error: an
	 * empty handler result would otherwise leave the request without any response. A result
	 * that is not the method's response type is answered with an error naming both types.
	 */
	private <T> T respond(AcpHandlerMethod handler, Class<T> responseType, Object request,
			@Nullable String sessionId, @Nullable SyncPromptContext syncContext) {
		Object result = invoke(handler, request, sessionId, syncContext);
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
	 * handling (see {@link #intercept}).
	 * @return the handler's result, or null when it produced none or an interceptor vetoed
	 * the call
	 */
	private @Nullable Object invoke(AcpHandlerMethod handler, Object request, @Nullable String sessionId,
			@Nullable SyncPromptContext syncContext) {
		return intercept(handler.getAcpMethod(), request, sessionId, syncContext, context -> {
			// Resolve arguments, invoke, and handle the return value
			@Nullable Object[] args = resolveArguments(handler, context);
			Object result = handler.invoke(args);
			return new Outcome(result, handler.getReturnType());
		});
	}

	/**
	 * The initialize response derived from the annotations, for an agent without an
	 * {@code @Initialize} method, produced through the interceptor chain as a declared
	 * handler's would be.
	 */
	private InitializeResponse deriveInitialize(AcpSchema.InitializeRequest request) {
		Object result = intercept(AcpSchema.METHOD_INITIALIZE, request, null, null,
				context -> new Outcome(advertisement.derive(request), null));
		if (!(result instanceof InitializeResponse response)) {
			throw new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR,
					"The initialize handler produced no response (an interceptor vetoed the call)");
		}
		return response;
	}

	/** The call an interceptor chain wraps. */
	@FunctionalInterface
	private interface Call {

		Outcome call(AcpInvocationContext context) throws Exception;

	}

	/**
	 * What a call produced: its result, and the declared return type that the return value
	 * handlers convert it by, or null for a result that is already the response.
	 */
	private record Outcome(@Nullable Object result, @Nullable AcpMethodParameter returnType) {
	}

	/**
	 * Runs the interceptor chain around {@code call}. The invocation sees this connection's
	 * agent and, once the client has sent initialize, the capabilities negotiated on it. A
	 * result with a declared return type passes through the return value handlers.
	 * @return the result, or null when there is none or an interceptor vetoed the call
	 */
	private @Nullable Object intercept(String acpMethod, Object request, @Nullable String sessionId,
			@Nullable SyncPromptContext syncContext, Call call) {

		AcpInvocationContext context = AcpInvocationContext.builder()
				.acpMethod(acpMethod)
				.request(request)
				.sessionId(sessionId)
				.syncPromptContext(syncContext)
				.promptContext(syncContext != null ? syncContext.async() : null)
				.capabilities(agent.getClientCapabilities())
				.agent(agent)
				.build();

		InterceptorChain chain = new InterceptorChain(interceptors);

		try {
			if (!chain.applyPreInvoke(context)) {
				return null;
			}
			Outcome outcome = call.call(context);
			Object result = chain.applyPostInvoke(context, outcome.result());
			AcpMethodParameter returnType = outcome.returnType();
			return (returnType != null) ? returnValueHandlers.handleReturnValue(result, returnType, context) : result;
		}
		catch (Exception e) {
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

	/**
	 * The handler annotations and the ACP method each one marks a handler for, in a stable order.
	 * @return the annotations, by annotation type
	 */
	static Map<Class<? extends Annotation>, String> handlerAnnotations() {
		Map<Class<? extends Annotation>, String> annotations = new LinkedHashMap<>();
		annotations.put(Initialize.class, AcpSchema.METHOD_INITIALIZE);
		annotations.put(Authenticate.class, AcpSchema.METHOD_AUTHENTICATE);
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
	private record Definition(Map<String, AcpHandlerMethod> handlers, AgentAdvertisement advertisement,
			ArgumentResolverComposite argumentResolvers,
			ReturnValueHandlerComposite returnValueHandlers, List<AcpInterceptor> interceptors,
			@Nullable Duration requestTimeout, Duration cancelGracePeriod, Duration maxPromptDuration,
			@Nullable ExecutorService handlerExecutor) {
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

		private final List<Class<?>> agentClasses = new ArrayList<>();

		private final List<ArgumentResolver> customArgumentResolvers = new ArrayList<>();

		private final List<ReturnValueHandler> customReturnValueHandlers = new ArrayList<>();

		private final List<AcpInterceptor> interceptors = new ArrayList<>();

		private @Nullable AcpAgentTransport transport;

		/** Null for the SDK's default, which the agent builder applies. */
		private @Nullable Duration requestTimeout;

		private Duration cancelGracePeriod = PromptTimeouts.DEFAULT_CANCEL_GRACE_PERIOD;

		private Duration maxPromptDuration = Duration.ZERO;

		private @Nullable ExecutorService handlerExecutor;

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
		 * Sets the executor the agent's handler methods run on, for example
		 * {@code Executors.newVirtualThreadPerTaskExecutor()} or a framework's worker pool, as
		 * {@code AcpAgent.SyncAgentBuilder#handlerExecutor} does. Without it they run on the SDK's
		 * shared pool of daemon threads. The executor must allow blocking; the SDK never shuts it
		 * down.
		 * @param executor the executor the handler methods run on
		 * @return this builder
		 * @throws IllegalArgumentException if {@code executor} is null
		 */
		public Builder handlerExecutor(ExecutorService executor) {
			if (executor == null) {
				throw new IllegalArgumentException("Executor must not be null");
			}
			this.handlerExecutor = executor;
			return this;
		}

		/**
		 * Set how long the agent waits for the client to answer a request the agent sends,
		 * such as a permission prompt or a file read. Default: the SDK's default request
		 * timeout, 60 seconds, the same as for a builder agent ({@code AcpAgent.sync(..)}) and
		 * a client.
		 * @param timeout the timeout, or {@code null} for the SDK's default
		 * @return this builder
		 */
		public Builder requestTimeout(@Nullable Duration timeout) {
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
				throw new IllegalStateException("Transport must be configured: call transport(..) before build(),"
						+ " or call buildFactory() for a listener transport");
			}
			return new AcpAgentSupport(definition(), transport);
		}

		/**
		 * Build the agent on the configured transport, start it, and block until the
		 * transport ends (stdin closes, or the agent is closed). The one call a stdio agent's
		 * {@code main} method needs:
		 * <pre>{@code
		 * AcpAgentSupport.create(new MyAgent()).transport(new StdioAcpAgentTransport()).run();
		 * }</pre>
		 * @throws IllegalStateException if no transport or agent bean is configured
		 */
		public void run() {
			build().run();
		}


		/**
		 * Build a factory for a listener transport, such as
		 * {@code StreamableHttpAcpAgentTransport} or {@code StreamableHttpAcpServlet}, that
		 * creates a fresh agent for each connection it accepts. Every agent invokes the same
		 * annotated handler instance, concurrently across connections, so its handlers must
		 * be thread-safe (see {@link AcpAgentSupport}). The factory captures the builder as
		 * it is now. The listener supplies a transport per connection, so a builder with a
		 * {@link #transport} set is refused.
		 * @return a factory creating one agent per connection
		 * @throws IllegalStateException if a transport was set, or no agent bean was given
		 */
		public AcpAgentFactory buildFactory() {
			if (this.transport != null) {
				throw new IllegalStateException("buildFactory() serves a listener transport, which supplies a"
						+ " transport per connection, but transport(..) was set: drop transport(..), or call build()"
						+ " for an agent on that transport");
			}
			Definition definition = definition();
			return AcpAgentFactory.sync(connection -> new AcpAgentSupport(definition, connection).getAgent());
		}

		/**
		 * The builder's configuration now, with the defaults after the custom entries.
		 * @throws IllegalStateException if an agent auth method has no {@code @Authenticate}
		 * handler to serve it
		 */
		private Definition definition() {
			if (agentClasses.isEmpty()) {
				throw new IllegalStateException("No @AcpAgent bean was given: use AcpAgentSupport.create(bean),"
						+ " or builder().agent(..) before building");
			}
			ArgumentResolverComposite argumentResolvers = new ArgumentResolverComposite()
				.addResolvers(customArgumentResolvers)
				.addResolvers(defaultResolvers());
			ReturnValueHandlerComposite returnValueHandlers = new ReturnValueHandlerComposite()
				.addHandlers(customReturnValueHandlers)
				.addHandlers(defaultReturnValueHandlers());
			Map<String, AcpHandlerMethod> handlers = Map.copyOf(this.handlers);
			HandlerSignatures.check(handlers, HANDLER_ANNOTATIONS, argumentResolvers, customArgumentResolvers,
					returnValueHandlers, customReturnValueHandlers);
			HandlerSignatures.checkSessionSetup(handlers);
			HandlerSignatures.checkPrompt(handlers, agentClasses.get(0).getName());
			AgentAdvertisement advertisement = AgentAdvertisement.of(List.copyOf(agentClasses), handlers,
					HANDLER_ANNOTATIONS);
			return new Definition(handlers, advertisement, argumentResolvers, returnValueHandlers,
					List.copyOf(interceptors), requestTimeout, cancelGracePeriod, maxPromptDuration,
					handlerExecutor);
		}

		/**
		 * Discovers the handler methods of {@code agentClass} and its superclasses, and binds
		 * them to the one instance the factory gives. The {@code @AcpAgent} annotation may be on
		 * a superclass, as it is for a proxy a framework generates (Spring CGLIB, Quarkus ArC,
		 * Micronaut AOP), whose subclass carries no annotations: handlers are then found on the
		 * user's class and invoked on the proxy, so its interceptors run. A method overridden in
		 * a subclass is one handler, invoked virtually, and bridge and synthetic methods are not
		 * handlers.
		 */
		private void discoverHandlers(Class<?> agentClass, Supplier<Object> instanceFactory) {
			Class<?> annotatedClass = annotatedAgentClass(agentClass);
			if (annotatedClass == null) {
				throw new IllegalArgumentException("Class must be annotated with @AcpAgent: " + agentClass.getName()
						+ " (neither it nor a superclass is)");
			}
			AgentAdvertisement.validate(annotatedClass);
			agentClasses.add(annotatedClass);
			// One instance for every handler and request: handlers share the agent's state
			Object agentInstance = instanceFactory.get();
			Set<String> seen = new HashSet<>();
			for (Class<?> type = agentClass; type != null && type != Object.class; type = type.getSuperclass()) {
				discoverDeclaredHandlers(type, agentInstance, seen);
			}
		}

		/**
		 * Registers the handlers {@code type} declares, other than those an annotated override
		 * below it already registered ({@code seen}, the signatures registered so far). An
		 * unannotated override, such as a proxy's, leaves the annotations to the method it
		 * overrides. A generic override's bridge stands for the erased method it overrides.
		 */
		private void discoverDeclaredHandlers(Class<?> type, Object agentInstance, Set<String> seen) {
			Set<String> registeredHere = new HashSet<>();
			for (Method method : type.getDeclaredMethods()) {
				if (!method.isBridge() && !method.isSynthetic() && !seen.contains(signature(method))
						&& discoverHandler(method, agentInstance)) {
					registeredHere.add(method.getName());
					seen.add(signature(method));
				}
			}
			for (Method method : type.getDeclaredMethods()) {
				if (method.isBridge() && registeredHere.contains(method.getName())) {
					seen.add(signature(method));
				}
			}
		}

		/**
		 * Registers {@code method} for the handler annotation it carries.
		 * @return whether it carries one
		 * @throws IllegalArgumentException if it carries more than one, or another method
		 * already handles its ACP method
		 */
		private boolean discoverHandler(Method method, Object agentInstance) {
			Map<String, String> marks = new LinkedHashMap<>();
			HANDLER_ANNOTATIONS.forEach((annotation, acpMethod) -> {
				if (method.isAnnotationPresent(annotation)) {
					marks.put(acpMethod, "@" + annotation.getSimpleName());
				}
			});
			String extensionMethod = extensionMethod(method);
			if (extensionMethod != null) {
				marks.put(extensionMethod, method.isAnnotationPresent(ExtNotification.class) ? "@ExtNotification"
						: "@ExtRequest");
			}
			if (marks.isEmpty()) {
				return false;
			}
			if (marks.size() > 1) {
				throw new IllegalArgumentException(describe(method) + " is annotated " + String.join(" and ", marks.values())
						+ "; a method handles one ACP method, so split it into one method per annotation");
			}
			Map.Entry<String, String> mark = marks.entrySet().iterator().next();
			AcpHandlerMethod existing = handlers.get(mark.getKey());
			if (existing != null) {
				throw new IllegalArgumentException(describe(existing.getMethod()) + " and " + describe(method)
						+ " are both " + mark.getValue() + " methods; keep one " + mark.getValue() + " method");
			}
			handlers.put(mark.getKey(), new AcpHandlerMethod(agentInstance, method, mark.getKey()));
			log.debug("Discovered {} handler: {}", mark.getValue(), method.getName());
			return true;
		}

		private static String describe(Method method) {
			return method.getDeclaringClass().getName() + "." + method.getName();
		}

		/** A method's name and parameter types, which an override shares. */
		private static String signature(Method method) {
			return method.getName() + Arrays.toString(method.getParameterTypes());
		}

		/** The first class from {@code type} up that is annotated {@code @AcpAgent}, or null. */
		private static @Nullable Class<?> annotatedAgentClass(Class<?> type) {
			for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
				if (candidate.isAnnotationPresent(com.agentclientprotocol.sdk.annotation.AcpAgent.class)) {
					return candidate;
				}
			}
			return null;
		}

		/**
		 * The extension method an {@link ExtRequest} or {@link ExtNotification} method
		 * handles, or null when it has neither annotation.
		 * @throws IllegalArgumentException if the name does not start with {@code _}, or the
		 * method takes more than one parameter for the params (its other parameters may only
		 * take its connection's capabilities or agent)
		 */
		private static @Nullable String extensionMethod(Method method) {
			ExtRequest request = method.getAnnotation(ExtRequest.class);
			ExtNotification notification = method.getAnnotation(ExtNotification.class);
			String name = (request != null) ? request.value() : (notification != null) ? notification.value() : null;
			if (name == null) {
				return null;
			}
			ExtensionMethods.requireExtension(name);
			rejectSessionParameters(method);
			long paramsParameters = Arrays.stream(method.getParameterTypes())
				.filter(type -> !ExtensionParamsResolver.isConnectionType(type))
				.count();
			if (paramsParameters > 1) {
				throw new IllegalArgumentException("Extension handler " + method.getName()
						+ " must take at most one parameter, which receives the params of " + name
						+ ", besides NegotiatedCapabilities, AcpSyncAgent or AcpAsyncAgent");
			}
			return name;
		}

		/**
		 * Rejects an extension handler's {@code @SessionId}, {@code @ConfigId} or
		 * {@code @ConfigValue} parameter: an extension method has no session, and the
		 * parameter would otherwise be taken for the params.
		 */
		private static void rejectSessionParameters(Method method) {
			for (java.lang.reflect.Parameter parameter : method.getParameters()) {
				List.of(SessionId.class, ConfigId.class, ConfigValue.class)
					.stream()
					.filter(parameter::isAnnotationPresent)
					.findFirst()
					.ifPresent(annotation -> {
						throw new IllegalArgumentException("Extension handler " + describe(method) + " has a @"
								+ annotation.getSimpleName() + " parameter, but an extension method has no session;"
								+ " read what it needs from its params instead");
					});
			}
		}

		private static List<ArgumentResolver> defaultResolvers() {
			// Built-in resolvers (order matters - first match wins), after the custom ones
			return List.of(
					new ExtensionParamsResolver(),
					new ConfigOptionResolver(),
					new InitializeRequestResolver(),
					new AuthenticateRequestResolver(),
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
					new AgentResolver(),
					new SessionIdResolver(),
					new CapabilitiesResolver());
		}

		private static List<ReturnValueHandler> defaultReturnValueHandlers() {
			// Built-in handlers (order matters - first match wins), after the custom ones.
			// Async values (Mono, CompletionStage, Publisher) are awaited; extension results are
			// any value, so their handler is last, after the async values and void.
			return List.of(new DirectResponseHandler(), new StringToPromptResponseHandler(), new VoidHandler(),
					new AsyncValueHandler(), new ExtensionResultHandler());
		}

	}

}
