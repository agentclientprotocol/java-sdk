/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandlerComposite;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolverComposite;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.ConfigId;
import com.agentclientprotocol.sdk.annotation.ConfigValue;
import com.agentclientprotocol.sdk.annotation.ExtNotification;
import com.agentclientprotocol.sdk.annotation.SessionId;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.ExtensionMethods;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

/**
 * Checks, when an agent is built, that each handler method can be called: every parameter has a
 * resolver and suits the method (its request type, a session id only where there is a session,
 * a prompt context only in a prompt method, config values only in a config option method), and
 * the return type gives the method's response. A mistake fails the build with a message naming
 * the class, the method and the fix, instead of failing every request at run time.
 *
 * <p>
 * Parameters a custom {@link ArgumentResolver} supports, and return types a custom
 * {@link ReturnValueHandler} supports, are the application's to make work and are not checked.
 * </p>
 */
final class HandlerSignatures {

	/**
	 * What a protocol method passes its handler and expects back.
	 * @param request the request (or notification) type
	 * @param response the response type, or null for a notification
	 * @param session whether the method belongs to a session, so a {@link SessionId} is set
	 */
	record Shape(Class<?> request, @Nullable Class<?> response, boolean session) {
	}

	private static final Map<String, Shape> SHAPES = shapes();

	private HandlerSignatures() {
	}

	private static Map<String, Shape> shapes() {
		Map<String, Shape> shapes = new LinkedHashMap<>();
		shapes.put(AcpSchema.METHOD_INITIALIZE,
				new Shape(AcpSchema.InitializeRequest.class, AcpSchema.InitializeResponse.class, false));
		shapes.put(AcpSchema.METHOD_AUTHENTICATE,
				new Shape(AcpSchema.AuthenticateRequest.class, AcpSchema.AuthenticateResponse.class, false));
		shapes.put(AcpSchema.METHOD_LOGOUT,
				new Shape(AcpSchema.LogoutRequest.class, AcpSchema.LogoutResponse.class, false));
		shapes.put(AcpSchema.METHOD_SESSION_NEW,
				new Shape(AcpSchema.NewSessionRequest.class, AcpSchema.NewSessionResponse.class, false));
		shapes.put(AcpSchema.METHOD_SESSION_LOAD,
				new Shape(AcpSchema.LoadSessionRequest.class, AcpSchema.LoadSessionResponse.class, true));
		shapes.put(AcpSchema.METHOD_SESSION_PROMPT,
				new Shape(AcpSchema.PromptRequest.class, AcpSchema.PromptResponse.class, true));
		shapes.put(AcpSchema.METHOD_SESSION_SET_MODE,
				new Shape(AcpSchema.SetSessionModeRequest.class, AcpSchema.SetSessionModeResponse.class, true));
		shapes.put(AcpSchema.METHOD_SESSION_LIST,
				new Shape(AcpSchema.ListSessionsRequest.class, AcpSchema.ListSessionsResponse.class, false));
		shapes.put(AcpSchema.METHOD_SESSION_CLOSE,
				new Shape(AcpSchema.CloseSessionRequest.class, AcpSchema.CloseSessionResponse.class, true));
		shapes.put(AcpSchema.METHOD_SESSION_DELETE,
				new Shape(AcpSchema.DeleteSessionRequest.class, AcpSchema.DeleteSessionResponse.class, true));
		shapes.put(AcpSchema.METHOD_SESSION_RESUME,
				new Shape(AcpSchema.ResumeSessionRequest.class, AcpSchema.ResumeSessionResponse.class, true));
		shapes.put(AcpSchema.METHOD_SESSION_FORK,
				new Shape(AcpSchema.ForkSessionRequest.class, AcpSchema.ForkSessionResponse.class, true));
		shapes.put(AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION, new Shape(AcpSchema.SetSessionConfigOptionRequest.class,
				AcpSchema.SetSessionConfigOptionResponse.class, true));
		shapes.put(AcpSchema.METHOD_PROVIDERS_LIST,
				new Shape(AcpSchema.ListProvidersRequest.class, AcpSchema.ListProvidersResponse.class, false));
		shapes.put(AcpSchema.METHOD_PROVIDERS_SET,
				new Shape(AcpSchema.SetProviderRequest.class, AcpSchema.SetProviderResponse.class, false));
		shapes.put(AcpSchema.METHOD_PROVIDERS_DISABLE,
				new Shape(AcpSchema.DisableProviderRequest.class, AcpSchema.DisableProviderResponse.class, false));
		shapes.put(AcpSchema.METHOD_SESSION_CANCEL, new Shape(AcpSchema.CancelNotification.class, null, true));
		return shapes;
	}

	/**
	 * The protocol method of every handler annotation and what it passes and expects.
	 * @return the shapes, by ACP method
	 */
	static Map<String, Shape> all() {
		return SHAPES;
	}

	/**
	 * Checks every handler of an agent being built.
	 * @throws IllegalStateException naming the first misuse found
	 */
	static void check(Map<String, AcpHandlerMethod> handlers,
			Map<Class<? extends Annotation>, String> handlerAnnotations, ArgumentResolverComposite resolvers,
			List<ArgumentResolver> customResolvers, ReturnValueHandlerComposite returnHandlers,
			List<ReturnValueHandler> customReturnHandlers) {
		Map<String, String> annotationNames = handlerAnnotations.entrySet()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getValue, entry -> "@" + entry.getKey().getSimpleName()));
		handlers.forEach((acpMethod, handler) -> {
			String annotation = annotationNames.getOrDefault(acpMethod,
					handler.getMethod().isAnnotationPresent(ExtNotification.class) ? "@ExtNotification"
							: "@ExtRequest");
			for (AcpMethodParameter parameter : handler.getParameters()) {
				if (customResolvers.stream().noneMatch(resolver -> resolver.supportsParameter(parameter))) {
					checkParameter(handler, annotation, parameter, resolvers);
				}
			}
			AcpMethodParameter returnType = handler.getReturnType();
			if (customReturnHandlers.stream().noneMatch(custom -> custom.supportsReturnType(returnType))) {
				checkReturnType(handler, annotation, returnType, returnHandlers);
			}
		});
	}

	/**
	 * Checks that an agent setting modes or config options has a {@code @NewSession} method to
	 * offer them: the default {@code session/new} answers with none, so a client would never see
	 * any to set. Likewise an agent that declares {@code @AcpAgent(additionalDirectories = true)}:
	 * the default {@code session/new} would drop the directories, which ACP forbids.
	 * @param agentClasses the agent's classes, whose {@code @AcpAgent} attributes are read
	 * @throws IllegalStateException if it has none
	 */
	static void checkSessionSetup(Map<String, AcpHandlerMethod> handlers, List<Class<?>> agentClasses) {
		if (handlers.containsKey(AcpSchema.METHOD_SESSION_NEW)) {
			return;
		}
		checkModeSetters(handlers);
		agentClasses.stream()
			.filter(agentClass -> agentClass.getAnnotation(AcpAgent.class).additionalDirectories())
			.findFirst()
			.ifPresent(agentClass -> {
				throw new IllegalStateException(agentClass.getName() + " declares @AcpAgent(additionalDirectories ="
						+ " true), which advertises that the agent uses the additional directories a client sends,"
						+ " but it has no @NewSession method to receive them; add one, or remove the attribute");
			});
	}

	/**
	 * Checks that the agent has a {@code @Prompt} method: every agent must answer
	 * {@code session/prompt}.
	 * @param agentClass the agent class, for the message
	 * @throws IllegalStateException if it has none
	 */
	static void checkPrompt(Map<String, AcpHandlerMethod> handlers, String agentClass) {
		if (!handlers.containsKey(AcpSchema.METHOD_SESSION_PROMPT)) {
			throw new IllegalStateException(agentClass + " has no @Prompt method, and every agent must answer"
					+ " session/prompt: add a @Prompt method");
		}
	}

	private static void checkModeSetters(Map<String, AcpHandlerMethod> handlers) {
		Map<String, String> setters = Map.of(AcpSchema.METHOD_SESSION_SET_MODE, "@SetSessionMode",
				AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION, "@SetSessionConfigOption");
		setters.forEach((acpMethod, annotation) -> {
			AcpHandlerMethod setter = handlers.get(acpMethod);
			if (setter != null) {
				Method method = setter.getMethod();
				throw new IllegalStateException(method.getDeclaringClass().getName() + "." + method.getName()
						+ " is a " + annotation + " method, but the agent has no @NewSession method, and the default"
						+ " session/new offers no modes or config options, so a client never sees any to set."
						+ " Add a @NewSession method that returns them");
			}
		});
	}

	private static void checkParameter(AcpHandlerMethod handler, String annotation, AcpMethodParameter parameter,
			ArgumentResolverComposite resolvers) {
		if (!resolvers.supportsParameter(parameter)) {
			throw misuse(handler, "parameter " + (parameter.getIndex() + 1) + " of type "
					+ parameter.getParameterType().getName() + ", which no argument resolver supplies. Take only the"
					+ " parameters " + annotation + " lists, or register an ArgumentResolver for the type with"
					+ " argumentResolver(..)");
		}
		if (!ExtensionMethods.isExtension(handler.getAcpMethod())) {
			checkRequestType(handler, annotation, parameter);
			checkSessionId(handler, parameter);
			checkConfigParameter(handler, parameter);
			checkPromptContext(handler, parameter);
		}
	}

	private static void checkRequestType(AcpHandlerMethod handler, String annotation, AcpMethodParameter parameter) {
		Class<?> type = parameter.getParameterType();
		Shape shape = shape(handler.getAcpMethod());
		if (type != shape.request() && SHAPES.values().stream().anyMatch(other -> other.request() == type)) {
			throw misuse(handler, "parameter " + (parameter.getIndex() + 1) + " of type " + type.getSimpleName()
					+ ", the request of another method. A " + annotation + " method receives a "
					+ shape.request().getSimpleName());
		}
	}

	private static void checkSessionId(AcpHandlerMethod handler, AcpMethodParameter parameter) {
		if (parameter.hasAnnotation(SessionId.class) && !shape(handler.getAcpMethod()).session()) {
			throw misuse(handler, "a @SessionId parameter, but " + handler.getAcpMethod()
					+ " belongs to no session, so there is no session id to give it. Remove the parameter");
		}
	}

	private static void checkConfigParameter(AcpHandlerMethod handler, AcpMethodParameter parameter) {
		boolean id = parameter.hasAnnotation(ConfigId.class);
		if ((id || parameter.hasAnnotation(ConfigValue.class))
				&& !AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION.equals(handler.getAcpMethod())) {
			throw misuse(handler, "a " + (id ? "@ConfigId" : "@ConfigValue")
					+ " parameter, which only a @SetSessionConfigOption method receives. Remove the parameter");
		}
	}

	private static void checkPromptContext(AcpHandlerMethod handler, AcpMethodParameter parameter) {
		Class<?> type = parameter.getParameterType();
		if ((PromptContext.class.isAssignableFrom(type) || SyncPromptContext.class.isAssignableFrom(type))
				&& !AcpSchema.METHOD_SESSION_PROMPT.equals(handler.getAcpMethod())) {
			throw misuse(handler, "a " + type.getSimpleName() + " parameter, which only a @Prompt method"
					+ " receives. Outside a prompt, take an AcpSyncAgent or AcpAsyncAgent parameter instead");
		}
	}

	private static void checkReturnType(AcpHandlerMethod handler, String annotation, AcpMethodParameter returnType,
			ReturnValueHandlerComposite returnHandlers) {
		String acpMethod = handler.getAcpMethod();
		if (ExtensionMethods.isExtension(acpMethod)) {
			boolean notification = handler.getMethod().isAnnotationPresent(ExtNotification.class);
			boolean isVoid = isVoid(returnType.getParameterType());
			if (isVoid && !notification) {
				throw misuse(handler, "return type void, but an @ExtRequest is answered with what its method"
						+ " returns. Return a result (an empty Map when there is nothing to return)");
			}
			if (!isVoid && notification) {
				throw misuse(handler, "return type " + returnType.getGenericType().getTypeName()
						+ ", but a notification gets no answer, so the value would be dropped. Return void, or"
						+ " use @ExtRequest if the client expects an answer");
			}
			return;
		}
		Class<?> response = shape(acpMethod).response();
		if (response != null) {
			checkResponse(handler, annotation, returnType, returnHandlers, response);
		}
	}

	private static void checkResponse(AcpHandlerMethod handler, String annotation, AcpMethodParameter returnType,
			ReturnValueHandlerComposite returnHandlers, Class<?> response) {
		Class<?> type = returnType.getParameterType();
		boolean prompt = AcpSchema.METHOD_SESSION_PROMPT.equals(handler.getAcpMethod());
		String expected = expected(annotation, response, prompt);
		if (prompt && isVoidOrString(type)) {
			return;
		}
		if (isVoid(type)) {
			throw misuse(handler, "return type void, but " + handler.getAcpMethod() + " needs a response" + expected);
		}
		if (!returnHandlers.supportsReturnType(returnType)) {
			throw misuse(handler, "return type " + type.getName() + ", which no return value handler accepts"
					+ expected + ", or register a ReturnValueHandler with returnValueHandler(..)");
		}
		if (!gives(returnType, response, prompt)) {
			throw misuse(handler, "return type " + returnType.getGenericType().getTypeName()
					+ ", which does not give a " + response.getSimpleName() + expected);
		}
	}

	/**
	 * Whether the return type can produce the response: it is related to it, or is a
	 * {@code Mono} (or other async type) of a related type or of an unknown type; a prompt
	 * method's may also produce a {@code String}.
	 */
	private static boolean gives(AcpMethodParameter returnType, Class<?> response, boolean prompt) {
		Class<?> type = returnType.getParameterType();
		Class<?> produced = isAsync(type) ? valueType(returnType.getGenericType()) : type;
		return produced == null || related(produced, response) || (prompt && produced == String.class);
	}

	private static String expected(String annotation, Class<?> response, boolean prompt) {
		return ". A " + annotation + " method returns a " + response.getSimpleName() + " or a Mono of one"
				+ (prompt ? " (or String or void)" : "");
	}

	/** A prompt method may return these, which end the turn. */
	private static boolean isVoidOrString(Class<?> type) {
		return isVoid(type) || type == String.class;
	}

	private static boolean isVoid(Class<?> type) {
		return type == void.class || type == Void.class;
	}

	/** Whether a value of one type can be the other: the same type, a supertype or a subtype. */
	private static boolean related(Class<?> produced, Class<?> response) {
		return produced.isAssignableFrom(response) || response.isAssignableFrom(produced);
	}

	/** Whether the type is one whose value arrives later, which the runtime waits for. */
	static boolean isAsync(Class<?> type) {
		return Publisher.class.isAssignableFrom(type) || java.util.concurrent.CompletionStage.class.isAssignableFrom(type);
	}

	/** The value type of {@code Mono<T>} and the like, when it is a class; null otherwise. */
	private static @Nullable Class<?> valueType(Type generic) {
		if (generic instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length == 1) {
			Type argument = parameterized.getActualTypeArguments()[0];
			if (argument instanceof Class<?> type) {
				return type;
			}
			if (argument instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> raw) {
				return raw;
			}
		}
		return null;
	}

	private static Shape shape(String acpMethod) {
		Shape shape = SHAPES.get(acpMethod);
		if (shape == null) {
			throw new IllegalStateException("No shape declared for " + acpMethod);
		}
		return shape;
	}

	private static IllegalStateException misuse(AcpHandlerMethod handler, String problem) {
		Method method = handler.getMethod();
		return new IllegalStateException(method.getDeclaringClass().getName() + "." + method.getName() + " (handling "
				+ handler.getAcpMethod() + ") has " + problem);
	}

}
