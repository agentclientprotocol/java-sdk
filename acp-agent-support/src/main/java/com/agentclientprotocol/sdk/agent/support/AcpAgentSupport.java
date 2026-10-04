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
 * Runs an ACP agent written with annotations. Give it an object whose class is marked
 * {@link com.agentclientprotocol.sdk.annotation.AcpAgent @AcpAgent}: it finds the handler methods,
 * answers {@code initialize} from the annotations, and serves the methods over a transport. Use it
 * when one method per request reads better than lambdas on the
 * {@link AcpAgent#sync(AcpAgentTransport) AcpAgent.sync(..)} builder, which it uses underneath. The
 * rules for the handler methods themselves (parameters, return values, errors, threads) are on
 * {@code @AcpAgent}.
 *
 * <pre>{@code
 * @AcpAgent(name = "my-agent", version = "1.0")
 * class MyAgent {
 *
 *     @LoadSession
 *     LoadSessionResponse load(LoadSessionRequest request) {
 *         return new LoadSessionResponse(null, null);
 *     }
 *
 *     @Prompt
 *     PromptResponse prompt(PromptRequest request, SyncPromptContext context) {
 *         context.sendMessage("Hello!");
 *         return PromptResponse.endTurn();
 *     }
 * }
 *
 * // In main: serve the agent on stdio until the client closes its input
 * AcpAgentSupport.create(new MyAgent()).transport(new StdioAcpAgentTransport()).run();
 * }</pre>
 *
 * <h2>Building and running</h2>
 * <p>{@link #create(Object)} returns a {@link Builder}. The builder finds the handler methods when
 * the bean is given to it and checks them when it builds, so a mistake, such as a parameter that no
 * resolver can fill or a missing {@code @Prompt} method, throws there with a message naming the
 * method and the fix, not at the first request. {@link Builder#build()} gives an agent on the
 * builder's transport: {@link #start()} starts it and returns, {@link #run()} starts it and blocks
 * until the transport ends, and {@link #close()} closes it (try-with-resources works).
 * {@link Builder#run()} does all of it in one call, for a {@code main} method.
 *
 * <p>A listener transport, such as {@code StreamableHttpAcpAgentTransport} from the
 * {@code acp-streamable-http-jetty} module, accepts many connections and needs one agent for each:
 * {@link Builder#buildFactory()} gives it an {@link AcpAgentFactory} instead.
 * <pre>{@code
 * AcpAgentFactory factory = AcpAgentSupport.create(new MyAgent()).buildFactory();
 * new StreamableHttpAcpAgentTransport(8080, factory).start().block();
 * }</pre>
 *
 * <h2>Answering {@code initialize}</h2>
 * <p>The agent answers {@code initialize} from its annotations, with no {@code @Initialize} method
 * needed: each handler annotation advertises the capability its ACP method needs (above,
 * {@code loadSession}), the {@code @AcpAgent} attributes give {@code agentInfo},
 * {@code authMethods} and the MCP transports, and the {@code @Prompt} attributes give the prompt
 * content the agent accepts. An {@code @Initialize} method's response is laid over the derived one;
 * see {@link com.agentclientprotocol.sdk.annotation.Initialize @Initialize} for the merge rule.
 * Without a {@code @NewSession} method, {@code session/new} is answered with a random UUID as the
 * session id.
 *
 * <h2>One handler bean, shared</h2>
 * <p>The annotated object is the application's bean, like a Spring controller: the builder finds
 * its handler methods once, and every agent the builder builds, every connection a factory serves
 * included, calls the same instance. Its handler methods are therefore called concurrently, from
 * different connections and from different sessions of one connection, and must be thread-safe;
 * keep per-session state in a concurrent map keyed by session id, not in plain fields. The same
 * holds for interceptors, argument resolvers and return value handlers. For the same reason a bean
 * cannot hold "its" agent: a handler method that needs the connection's agent (to send a session
 * update outside a prompt turn) or its negotiated capabilities takes an {@code AcpSyncAgent},
 * {@code AcpAsyncAgent} or {@code NegotiatedCapabilities} parameter, filled for each call from the
 * connection the request arrived on.
 *
 * <h2>Extension points</h2>
 * <p>Each call of a handler method runs these steps on the handler's thread: the
 * {@link AcpInterceptor}s' {@code preInvoke}, an {@link ArgumentResolver} for each parameter, the
 * method itself, the interceptors' {@code postInvoke}, a {@link ReturnValueHandler} that turns the
 * returned value into the response, and the interceptors' {@code afterCompletion}. Add your own
 * with {@link Builder#interceptor}, {@link Builder#argumentResolver} and
 * {@link Builder#returnValueHandler}. Custom resolvers and return value handlers are asked before
 * the built-in ones, so they can also replace one. The {@code initialize} answer derived from the
 * annotations, and the default {@code session/new} answer given when there is no
 * {@code @NewSession} method, pass through the interceptors as a handler method's would.
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
	 * Starts a builder for an agent served by {@code agentInstance}, as
	 * {@code builder().agent(agentInstance)} does. Its handler methods are found here, once.
	 * @param agentInstance the handler bean, an instance of a class marked {@code @AcpAgent} (or of
	 * a subclass of one)
	 * @return a new builder with the bean added
	 * @throws IllegalArgumentException if the bean's handler methods are malformed (see
	 * {@link Builder#agent(Object)})
	 */
	public static Builder create(Object agentInstance) {
		return new Builder().agent(agentInstance);
	}

	/**
	 * Starts a builder for an agent served by a new instance of {@code agentClass}, as
	 * {@code builder().agent(agentClass)} does. The class is instantiated once, here, with its
	 * no-argument constructor, and that instance serves every request.
	 * @param agentClass the class marked {@code @AcpAgent}
	 * @return a new builder with the bean added
	 * @throws IllegalArgumentException if the class cannot be instantiated, or its handler methods
	 * are malformed (see {@link Builder#agent(Object)})
	 */
	public static Builder create(Class<?> agentClass) {
		return new Builder().agent(agentClass);
	}

	/**
	 * Starts an empty builder. Add the handler bean with one of the {@code agent(..)} methods
	 * before building; {@link Builder#agent(Class, Supplier)} suits a framework that creates the
	 * bean itself.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Starts the agent: its transport starts and it begins answering the client. Returns at once;
	 * the transport's threads serve the client. Call it once.
	 * @throws IllegalStateException if the agent was already started
	 */
	public void start() {
		log.info("Starting annotation-based ACP agent");
		agent.start();
	}

	/**
	 * Starts the agent and blocks until its transport ends: on stdio, once the client has closed
	 * the agent's input and every request received before has been answered; on any transport, once
	 * {@link #close()} has been called. Use it to keep a {@code main} method alive.
	 * @throws IllegalStateException if the agent was already started
	 */
	public void run() {
		start();
		agent.awaitTermination();
	}

	/**
	 * Closes the agent as {@link AcpSyncAgent#close()} does: gracefully, waiting at most 10
	 * seconds, then at once if that failed or took longer. A {@link #run()} in progress then
	 * returns. Try-with-resources calls it.
	 */
	@Override
	public void close() {
		log.info("Closing annotation-based ACP agent");
		agent.close();
	}

	/**
	 * Returns the sync agent this object runs. Use it to send session updates and requests to the
	 * client from outside a handler method, such as from a background task, once the agent has
	 * started. Inside a handler method, take an {@code AcpSyncAgent} parameter instead, which works
	 * under {@link Builder#buildFactory()} too.
	 * @return the agent
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
				() -> agent.initializeHandler(req -> answerByDefault(AcpSchema.METHOD_INITIALIZE, req,
						InitializeResponse.class, () -> advertisement.derive(req))));
		bind(AcpSchema.METHOD_SESSION_NEW,
				handler -> agent.newSessionHandler(req -> respond(handler, NewSessionResponse.class, req, null)),
				() -> agent.newSessionHandler(req -> answerByDefault(AcpSchema.METHOD_SESSION_NEW, req,
						NewSessionResponse.class, () -> new NewSessionResponse(UUID.randomUUID().toString(), null, null))));
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
	 * The SDK's own answer for an ACP method the agent declares no handler method for (the
	 * initialize response derived from the annotations, or a session/new with a random id),
	 * produced through the interceptor chain as a declared handler's would be.
	 */
	private <T> T answerByDefault(String acpMethod, Object request, Class<T> responseType, Supplier<T> answer) {
		Object result = intercept(acpMethod, request, null, null, context -> new Outcome(answer.get(), null));
		if (!responseType.isInstance(result)) {
			throw new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR,
					"The " + acpMethod + " handler produced no response (an interceptor vetoed the call)");
		}
		return responseType.cast(result);
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
			Object replacement = onError(chain, context, e);
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

	/**
	 * Asks the interceptors' {@code onError} for a replacement. An {@link AcpProtocolException}
	 * one of them throws is the call's answer; anything else it throws is a fault in the
	 * interceptor, answered as an internal error with the call's own failure kept as suppressed.
	 */
	private static @Nullable Object onError(InterceptorChain chain, AcpInvocationContext context, Exception failure) {
		try {
			return chain.applyOnError(context, failure);
		}
		catch (AcpProtocolException answer) {
			return rethrow(answer, failure);
		}
		catch (RuntimeException fault) {
			return rethrow(new IllegalStateException("An interceptor's onError failed for " + context.getAcpMethod(),
					fault), failure);
		}
	}

	@SuppressWarnings("ReferenceEquality") // the same exception instance, rethrown, cannot suppress itself
	private static Object rethrow(RuntimeException thrown, Exception failure) {
		if (thrown != failure) {
			thrown.addSuppressed(failure);
		}
		throw thrown;
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
	 * Configures an annotated agent and builds it: an {@link AcpAgentSupport} on one transport
	 * ({@link #build()}, {@link #run()}), or an {@link AcpAgentFactory} for a listener transport
	 * ({@link #buildFactory()}). Get one from {@link AcpAgentSupport#create(Object)} or
	 * {@link AcpAgentSupport#builder()}.
	 *
	 * <p>A builder can be built any number of times. Each {@link #build()} and
	 * {@link #buildFactory()} puts the default argument resolvers and return value handlers after
	 * the custom ones without changing the builder, and what is built is not affected by later
	 * changes to the builder. Every agent built from one builder calls the same handler bean (see
	 * {@link AcpAgentSupport}). A builder is not thread-safe: configure it on one thread.
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
		 * Adds a handler bean: finds the handler methods of its class and superclasses now, and
		 * binds them to this instance. Add more than one bean to split an agent across classes:
		 * their handler methods combine, the first bean's {@code @AcpAgent} names the agent, and
		 * their auth methods and MCP transports are combined.
		 * @param agentInstance the handler bean, an instance of a class marked {@code @AcpAgent}
		 * (or of a subclass of one)
		 * @return this builder
		 * @throws IllegalArgumentException if neither the bean's class nor a superclass is marked
		 * {@code @AcpAgent}, a method carries two handler annotations, two methods (in this bean or
		 * one added before) answer the same ACP method, an extension method is malformed (a name
		 * without the {@code _} prefix, more than one params parameter, or a session parameter), or
		 * an {@code @AcpAgent} auth method is malformed
		 */
		public Builder agent(Object agentInstance) {
			discoverHandlers(agentInstance.getClass(), () -> agentInstance);
			return this;
		}

		/**
		 * Adds a handler bean created from {@code agentClass} with its no-argument constructor, as
		 * {@link #agent(Object)} does. The class is instantiated once, here, and that instance
		 * serves every request.
		 * @param agentClass the class marked {@code @AcpAgent}
		 * @return this builder
		 * @throws IllegalArgumentException if the class cannot be instantiated, or for the reasons
		 * {@link #agent(Object)} gives
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
		 * Adds a handler bean that a framework creates, as {@link #agent(Object)} does. The handler
		 * methods are found on {@code agentClass} and its superclasses; {@code factory} is called
		 * once, here, and the object it returns serves every request. That object may be a subclass
		 * of {@code agentClass}, such as a proxy the framework generates, so its interceptors run.
		 * @param agentClass the class marked {@code @AcpAgent}
		 * @param factory gives the handler bean, an instance of {@code agentClass}
		 * @param <T> the bean type
		 * @return this builder
		 * @throws IllegalArgumentException for the reasons {@link #agent(Object)} gives
		 */
		public <T> Builder agent(Class<T> agentClass, Supplier<T> factory) {
			discoverHandlers(agentClass, factory::get);
			return this;
		}

		/**
		 * Sets the transport that {@link #build()} and {@link #run()} serve the agent on, such as a
		 * {@code StdioAcpAgentTransport}. Leave it unset for {@link #buildFactory()}, whose
		 * listener transport supplies one per connection.
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
		 * {@link AcpAgent.SyncAgentBuilder#handlerExecutor(ExecutorService)} does. Without it they
		 * run on the SDK's shared pool of daemon threads. The executor must allow blocking; the SDK
		 * never shuts it down.
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
		 * Sets how long the agent waits for the client to answer a request the agent sends, such as
		 * a permission prompt or a file read. The default is the SDK's default request timeout, 60
		 * seconds, the same as for a builder agent ({@code AcpAgent.sync(..)}) and a client.
		 * @param timeout the timeout, or {@code null} for the SDK's default
		 * @return this builder
		 */
		public Builder requestTimeout(@Nullable Duration timeout) {
			this.requestTimeout = timeout;
			return this;
		}

		/**
		 * Sets how long a {@code @Prompt} method has to return after {@code session/cancel} before
		 * the agent answers the prompt with stop reason {@code cancelled} itself and interrupts the
		 * method's thread. Default 60 seconds; {@link Duration#ZERO} for none. See
		 * {@link AcpAgent.SyncAgentBuilder#cancelGracePeriod(Duration)}. The value is checked when
		 * an agent is built, not here.
		 * @param gracePeriod the grace period; zero for none, not negative
		 * @return this builder
		 */
		public Builder cancelGracePeriod(Duration gracePeriod) {
			this.cancelGracePeriod = gracePeriod;
			return this;
		}

		/**
		 * Sets how long a prompt turn may run before the agent answers it with error {@code -32800}
		 * (request cancelled). Default none ({@link Duration#ZERO}). See
		 * {@link AcpAgent.SyncAgentBuilder#maxPromptDuration(Duration)}. The value is checked when
		 * an agent is built, not here.
		 * @param maxDuration the maximum prompt duration; zero for none, not negative
		 * @return this builder
		 */
		public Builder maxPromptDuration(Duration maxDuration) {
			this.maxPromptDuration = maxDuration;
			return this;
		}

		/**
		 * Adds an interceptor that runs around every handler method call. Interceptors run in
		 * {@link AcpInterceptor#getOrder()} order, and those with the same order in the order they
		 * were added.
		 * @param interceptor the interceptor, not null
		 * @return this builder
		 */
		public Builder interceptor(AcpInterceptor interceptor) {
			this.interceptors.add(interceptor);
			return this;
		}

		/**
		 * Adds an argument resolver, for a parameter type the built-in resolvers do not supply or
		 * to replace one of them: custom resolvers are asked before the built-in ones, in the order
		 * they were added. A parameter a custom resolver supports is not checked when the agent is
		 * built.
		 * @param resolver the resolver
		 * @return this builder
		 * @throws IllegalArgumentException if {@code resolver} is null
		 */
		public Builder argumentResolver(ArgumentResolver resolver) {
			Assert.notNull(resolver, "The resolver must not be null");
			this.customArgumentResolvers.add(resolver);
			return this;
		}

		/**
		 * Adds a return value handler, for a return type the built-in handlers do not accept or to
		 * replace one of them: custom handlers are asked before the built-in ones, in the order
		 * they were added. A return type a custom handler supports is not checked when the agent is
		 * built.
		 * @param handler the handler
		 * @return this builder
		 * @throws IllegalArgumentException if {@code handler} is null
		 */
		public Builder returnValueHandler(ReturnValueHandler handler) {
			Assert.notNull(handler, "The handler must not be null");
			this.customReturnValueHandlers.add(handler);
			return this;
		}

		/**
		 * Builds an agent on the configured transport. It is not started: call
		 * {@link AcpAgentSupport#start()} or {@link AcpAgentSupport#run()}. May be called again,
		 * with the same or another transport, for another agent that calls the same handler bean.
		 * @return the agent
		 * @throws IllegalStateException if no transport or no handler bean is configured, or a
		 * handler method cannot be served: it takes a parameter that no resolver supplies or that
		 * does not suit its method, or returns a type that cannot give its method's response; there
		 * is no {@code @Prompt} method; a {@code @SetSessionMode} or
		 * {@code @SetSessionConfigOption} method has no {@code @NewSession} method to offer modes
		 * or config options; or an auth method has no {@code @Authenticate} method to serve it
		 * @throws IllegalArgumentException if a duration set on this builder is negative
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
		 * Builds the agent on the configured transport, starts it, and blocks until the transport
		 * ends (on stdio, once the client closes the agent's input). The one call a stdio agent's
		 * {@code main} method needs:
		 * <pre>{@code
		 * AcpAgentSupport.create(new MyAgent()).transport(new StdioAcpAgentTransport()).run();
		 * }</pre>
		 * @throws IllegalStateException for the reasons {@link #build()} gives
		 * @throws IllegalArgumentException if a duration set on this builder is negative
		 */
		public void run() {
			build().run();
		}


		/**
		 * Builds a factory for a listener transport, such as
		 * {@code StreamableHttpAcpAgentTransport} or {@code StreamableHttpAcpServlet}, that creates
		 * a fresh agent for each connection it accepts. Every agent calls the same handler bean,
		 * concurrently across connections, so its handler methods must be thread-safe (see
		 * {@link AcpAgentSupport}). The factory captures the builder as it is now. The listener
		 * supplies a transport per connection, so a builder with a {@link #transport} set is
		 * refused. A negative duration is not checked here: each connection's agent then fails to
		 * build.
		 * @return a factory creating one agent per connection
		 * @throws IllegalStateException if a transport was set, or for the reasons {@link #build()}
		 * gives (other than the transport)
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
