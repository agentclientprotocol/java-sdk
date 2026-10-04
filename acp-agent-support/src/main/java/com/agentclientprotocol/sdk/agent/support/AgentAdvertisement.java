/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.AuthMethod;
import com.agentclientprotocol.sdk.annotation.Authenticate;
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
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentAuthCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.Implementation;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionCapabilities;
import org.jspecify.annotations.Nullable;

/**
 * What an annotated agent advertises in its {@code initialize} response, derived from its
 * classes once, when the builder builds: the capabilities its handler annotations imply
 * ({@link #mappings()}), its {@link AuthMethod}s, the MCP transports and prompt content its
 * {@link AcpAgent} and {@link Prompt} attributes declare, and its {@code agentInfo}.
 *
 * <p>
 * Per connection, {@link #derive(InitializeRequest)} turns it into the
 * response to one client: its protocol version negotiated, and the terminal auth methods left
 * out unless the client supports them. {@link #merge} lays an {@link Initialize} method's
 * response over that derived one.
 * </p>
 */
final class AgentAdvertisement {

	/** The protocol versions this SDK speaks. */
	private static final Set<Integer> SUPPORTED_PROTOCOL_VERSIONS = Set.of(AcpSchema.LATEST_PROTOCOL_VERSION);

	/** The version sent when neither {@code @AcpAgent} nor the jar manifest gives one. */
	static final String UNKNOWN_VERSION = "unknown";

	/** An empty capability object, {@code {}}, which advertises a session method. */
	private static final Map<String, Object> SUPPORTED = Map.of();

	/** What {@code agentCapabilities.sessionCapabilities} carries. */
	private static final Set<Advertises> SESSION_CAPABILITIES = EnumSet.of(Advertises.LIST_SESSIONS,
			Advertises.CLOSE_SESSION, Advertises.RESUME_SESSION, Advertises.DELETE_SESSION, Advertises.FORK_SESSION,
			Advertises.ADDITIONAL_DIRECTORIES);

	/**
	 * What a handler annotation, or an {@link AcpAgent} attribute, advertises in the initialize
	 * response.
	 */
	enum Advertises {

		/** Nothing: ACP requires every agent to support the method. */
		BASELINE,

		/** Nothing: the method is the initialize exchange itself. */
		INITIALIZE,

		/** {@code agentCapabilities.loadSession}. */
		LOAD_SESSION,

		/** {@code agentCapabilities.sessionCapabilities.list}. */
		LIST_SESSIONS,

		/** {@code agentCapabilities.sessionCapabilities.resume}. */
		RESUME_SESSION,

		/** {@code agentCapabilities.sessionCapabilities.close}. */
		CLOSE_SESSION,

		/** {@code agentCapabilities.sessionCapabilities.delete}. */
		DELETE_SESSION,

		/** {@code agentCapabilities.sessionCapabilities.fork} (unstable). */
		FORK_SESSION,

		/** {@code agentCapabilities.sessionCapabilities.additionalDirectories}, from {@link AcpAgent}. */
		ADDITIONAL_DIRECTORIES,

		/** {@code agentCapabilities.providers} (unstable). */
		PROVIDERS,

		/** {@code agentCapabilities.auth.logout}. */
		LOGOUT,

		/** Nothing itself: {@code authMethods} come from {@link AcpAgent#authMethods()}. */
		AUTH_METHODS,

		/**
		 * Nothing: what the method sets is offered per session, in the modes or config
		 * options of the session/new, session/load and session/resume responses.
		 */
		PER_SESSION,

		/** Nothing: extension methods are agreed outside the protocol (or in {@code _meta}). */
		EXTENSION

	}

	private static final Map<Class<? extends Annotation>, Advertises> MAPPINGS = createMappings();

	private final Set<Advertises> advertised;

	private final List<AuthMethod> authMethods;

	private final boolean mcpHttp;

	private final boolean mcpSse;

	private final @Nullable Prompt prompt;

	private final Implementation agentInfo;

	private AgentAdvertisement(Set<Advertises> advertised, List<AuthMethod> authMethods, boolean mcpHttp,
			boolean mcpSse, @Nullable Prompt prompt, Implementation agentInfo) {
		this.advertised = advertised;
		this.authMethods = authMethods;
		this.mcpHttp = mcpHttp;
		this.mcpSse = mcpSse;
		this.prompt = prompt;
		this.agentInfo = agentInfo;
	}

	/**
	 * Every handler annotation and what it advertises; an annotation without an entry fails
	 * {@code HandlerAnnotationCoverageTest}.
	 * @return the mappings, by annotation type
	 */
	static Map<Class<? extends Annotation>, Advertises> mappings() {
		return MAPPINGS;
	}

	private static Map<Class<? extends Annotation>, Advertises> createMappings() {
		Map<Class<? extends Annotation>, Advertises> mappings = new LinkedHashMap<>();
		mappings.put(Initialize.class, Advertises.INITIALIZE);
		mappings.put(Authenticate.class, Advertises.AUTH_METHODS);
		mappings.put(Logout.class, Advertises.LOGOUT);
		mappings.put(NewSession.class, Advertises.BASELINE);
		mappings.put(LoadSession.class, Advertises.LOAD_SESSION);
		mappings.put(Prompt.class, Advertises.BASELINE);
		mappings.put(Cancel.class, Advertises.BASELINE);
		mappings.put(SetSessionMode.class, Advertises.PER_SESSION);
		mappings.put(SetSessionConfigOption.class, Advertises.PER_SESSION);
		mappings.put(ListSessions.class, Advertises.LIST_SESSIONS);
		mappings.put(CloseSession.class, Advertises.CLOSE_SESSION);
		mappings.put(DeleteSession.class, Advertises.DELETE_SESSION);
		mappings.put(ResumeSession.class, Advertises.RESUME_SESSION);
		mappings.put(ForkSession.class, Advertises.FORK_SESSION);
		mappings.put(ListProviders.class, Advertises.PROVIDERS);
		mappings.put(SetProvider.class, Advertises.PROVIDERS);
		mappings.put(DisableProvider.class, Advertises.PROVIDERS);
		mappings.put(ExtRequest.class, Advertises.EXTENSION);
		mappings.put(ExtNotification.class, Advertises.EXTENSION);
		return Collections.unmodifiableMap(mappings);
	}

	/**
	 * Checks an agent class's {@link AcpAgent#authMethods()}: unique ids, and each terminal
	 * method's environment written {@code NAME=value}.
	 * @param agentClass the agent class
	 * @throws IllegalArgumentException if a method is malformed
	 */
	static void validate(Class<?> agentClass) {
		AcpAgent agent = agentClass.getAnnotation(AcpAgent.class);
		if (agent == null) {
			return;
		}
		Set<String> ids = new LinkedHashSet<>();
		for (AuthMethod method : agent.authMethods()) {
			if (!ids.add(method.id())) {
				throw new IllegalArgumentException(
						"Duplicate auth method id '" + method.id() + "' on " + agentClass.getName());
			}
			for (String entry : method.env()) {
				if (entry.indexOf('=') <= 0) {
					throw new IllegalArgumentException("Auth method '" + method.id() + "' on " + agentClass.getName()
							+ " has env entry '" + entry + "', which is not NAME=value");
				}
			}
		}
	}

	/**
	 * Derives the advertisement of the registered agent classes and their handlers. When
	 * several classes are registered, their auth methods and MCP transports are combined, and
	 * the first class names the agent.
	 * @param agentClasses the registered {@code @AcpAgent} classes, in registration order
	 * @param handlers the handlers, by ACP method
	 * @param handlerAnnotations the handler annotations and the ACP method each marks
	 * @return the advertisement
	 * @throws IllegalStateException if an agent auth method is declared without an
	 * {@link Authenticate} handler to serve it
	 */
	static AgentAdvertisement of(List<Class<?>> agentClasses, Map<String, AcpHandlerMethod> handlers,
			Map<Class<? extends Annotation>, String> handlerAnnotations) {
		Set<Advertises> advertised = new LinkedHashSet<>();
		handlerAnnotations.forEach((annotation, acpMethod) -> {
			if (handlers.containsKey(acpMethod)) {
				advertised.add(MAPPINGS.get(annotation));
			}
		});
		Map<String, AuthMethod> authMethods = new LinkedHashMap<>();
		boolean mcpHttp = false;
		boolean mcpSse = false;
		for (Class<?> agentClass : agentClasses) {
			AcpAgent agent = agentClass.getAnnotation(AcpAgent.class);
			for (AuthMethod method : agent.authMethods()) {
				authMethods.put(method.id(), method);
			}
			mcpHttp |= agent.mcpHttp();
			mcpSse |= agent.mcpSse();
		}
		agentClasses.stream()
			.filter(agentClass -> agentClass.getAnnotation(AcpAgent.class).additionalDirectories())
			.findAny()
			.ifPresent(agentClass -> advertised.add(Advertises.ADDITIONAL_DIRECTORIES));
		if (!handlers.containsKey(AcpSchema.METHOD_AUTHENTICATE)) {
			authMethods.values()
				.stream()
				.filter(method -> method.type() == AuthMethod.Type.AGENT)
				.findFirst()
				.ifPresent(method -> {
					throw new IllegalStateException("Auth method '" + method.id()
							+ "' is an agent method, which the client passes to authenticate, but the agent has no"
							+ " @Authenticate handler; add one, or declare the method AuthMethod.Type.TERMINAL");
				});
		}
		AcpHandlerMethod promptHandler = handlers.get(AcpSchema.METHOD_SESSION_PROMPT);
		Prompt prompt = (promptHandler != null) ? promptHandler.getMethod().getAnnotation(Prompt.class) : null;
		return new AgentAdvertisement(Collections.unmodifiableSet(advertised), List.copyOf(authMethods.values()),
				mcpHttp, mcpSse, prompt, agentInfo(agentClasses));
	}

	private static Implementation agentInfo(List<Class<?>> agentClasses) {
		if (agentClasses.isEmpty()) {
			return new Implementation("acp-agent", UNKNOWN_VERSION);
		}
		Class<?> agentClass = agentClasses.get(0);
		AcpAgent agent = agentClass.getAnnotation(AcpAgent.class);
		String name = agent.name().isBlank() ? agentClass.getSimpleName() : agent.name();
		String version = agent.version();
		if (version.isBlank()) {
			Package pkg = agentClass.getPackage();
			String manifestVersion = (pkg != null) ? pkg.getImplementationVersion() : null;
			version = (manifestVersion != null && !manifestVersion.isBlank()) ? manifestVersion : UNKNOWN_VERSION;
		}
		return new Implementation(name, version, agent.title().isBlank() ? null : agent.title());
	}

	/**
	 * The response derived for one client's initialize request.
	 * @param request the client's request
	 * @return the derived response
	 */
	InitializeResponse derive(InitializeRequest request) {
		AcpSchema.ClientCapabilities client = request.clientCapabilities();
		boolean terminalAuth = client != null && client.auth() != null
				&& Boolean.TRUE.equals(client.auth().terminal());
		List<AcpSchema.AuthMethod> methods = new ArrayList<>();
		for (AuthMethod method : this.authMethods) {
			if (method.type() == AuthMethod.Type.TERMINAL && !terminalAuth) {
				continue;
			}
			methods.add(toSchema(method));
		}
		return new InitializeResponse(negotiate(request.protocolVersion()), capabilities(),
				methods.isEmpty() ? null : List.copyOf(methods), this.agentInfo, null);
	}

	/**
	 * The protocol version to answer: the client's when this SDK speaks it, otherwise the
	 * latest this SDK speaks (ACP v1, Initialization, version negotiation).
	 */
	static int negotiate(@Nullable Integer requested) {
		return (requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested)) ? requested
				: AcpSchema.LATEST_PROTOCOL_VERSION;
	}

	private AgentCapabilities capabilities() {
		boolean anySession = SESSION_CAPABILITIES.stream().anyMatch(advertised::contains);
		SessionCapabilities sessions = anySession ? new SessionCapabilities(supported(Advertises.LIST_SESSIONS),
				supported(Advertises.CLOSE_SESSION), supported(Advertises.RESUME_SESSION),
				supported(Advertises.DELETE_SESSION), supported(Advertises.ADDITIONAL_DIRECTORIES),
				supported(Advertises.FORK_SESSION)) : null;
		Prompt prompt = this.prompt;
		return AgentCapabilities.builder()
			.loadSession(advertised.contains(Advertises.LOAD_SESSION))
			.sessionCapabilities(sessions)
			.mcpCapabilities(new McpCapabilities(this.mcpHttp, this.mcpSse))
			.promptCapabilities((prompt != null)
					? new PromptCapabilities(prompt.audio(), prompt.embeddedContext(), prompt.image())
					: new PromptCapabilities())
			.auth(advertised.contains(Advertises.LOGOUT) ? AgentAuthCapabilities.withLogout() : null)
			.providers(advertised.contains(Advertises.PROVIDERS) ? new AcpSchema.ProvidersCapabilities(null) : null)
			.build();
	}

	private @Nullable Object supported(Advertises capability) {
		return advertised.contains(capability) ? SUPPORTED : null;
	}

	private static AcpSchema.AuthMethod toSchema(AuthMethod method) {
		String description = method.description().isEmpty() ? null : method.description();
		if (method.type() == AuthMethod.Type.AGENT) {
			return new AcpSchema.AuthMethodAgent(method.id(), method.name(), description);
		}
		Map<String, String> env = new LinkedHashMap<>();
		for (String entry : method.env()) {
			int equals = entry.indexOf('=');
			env.put(entry.substring(0, equals), entry.substring(equals + 1));
		}
		return new AcpSchema.AuthMethodTerminal(method.id(), method.name(), description,
				(method.args().length == 0) ? null : List.of(method.args()), env.isEmpty() ? null : Map.copyOf(env),
				null);
	}

	/**
	 * Lays the response of an {@link Initialize} method over the derived one: a capability
	 * is advertised when either advertises it; the returned auth methods follow the derived
	 * ones, replacing any with the same id; the protocol version, and the agent info and
	 * {@code _meta} when not null, are the returned ones.
	 * @param derived the derived response
	 * @param returned the response the {@code @Initialize} method returned
	 * @return the response sent to the client
	 */
	static InitializeResponse merge(InitializeResponse derived, InitializeResponse returned) {
		Map<String, AcpSchema.AuthMethod> methods = new LinkedHashMap<>();
		for (List<AcpSchema.AuthMethod> list : List.of(nullToEmpty(derived.authMethods()),
				nullToEmpty(returned.authMethods()))) {
			for (AcpSchema.AuthMethod method : list) {
				methods.remove(method.id());
				methods.put(method.id(), method);
			}
		}
		return new InitializeResponse(
				(returned.protocolVersion() != null) ? returned.protocolVersion() : derived.protocolVersion(),
				merge(derived.agentCapabilities(), returned.agentCapabilities()),
				methods.isEmpty() ? null : List.copyOf(methods.values()),
				(returned.agentInfo() != null) ? returned.agentInfo() : derived.agentInfo(),
				(returned.meta() != null) ? returned.meta() : derived.meta());
	}

	private static <T> List<T> nullToEmpty(@Nullable List<T> list) {
		return (list != null) ? list : List.of();
	}

	private static @Nullable AgentCapabilities merge(@Nullable AgentCapabilities derived,
			@Nullable AgentCapabilities returned) {
		if (derived == null || returned == null) {
			return (derived != null) ? derived : returned;
		}
		return new AgentCapabilities(or(derived.loadSession(), returned.loadSession()),
				merge(derived.sessionCapabilities(), returned.sessionCapabilities()),
				merge(derived.mcpCapabilities(), returned.mcpCapabilities()),
				merge(derived.promptCapabilities(), returned.promptCapabilities()),
				merge(derived.auth(), returned.auth()), either(returned.providers(), derived.providers()),
				either(returned.meta(), derived.meta()));
	}

	private static @Nullable SessionCapabilities merge(@Nullable SessionCapabilities derived,
			@Nullable SessionCapabilities returned) {
		if (derived == null || returned == null) {
			return (derived != null) ? derived : returned;
		}
		return new SessionCapabilities(either(returned.list(), derived.list()), either(returned.close(), derived.close()),
				either(returned.resume(), derived.resume()), either(returned.delete(), derived.delete()),
				either(returned.additionalDirectories(), derived.additionalDirectories()),
				either(returned.fork(), derived.fork()), either(returned.meta(), derived.meta()));
	}

	private static @Nullable McpCapabilities merge(@Nullable McpCapabilities derived,
			@Nullable McpCapabilities returned) {
		if (derived == null || returned == null) {
			return (derived != null) ? derived : returned;
		}
		return new McpCapabilities(or(derived.http(), returned.http()), or(derived.sse(), returned.sse()),
				either(returned.meta(), derived.meta()));
	}

	private static @Nullable PromptCapabilities merge(@Nullable PromptCapabilities derived,
			@Nullable PromptCapabilities returned) {
		if (derived == null || returned == null) {
			return (derived != null) ? derived : returned;
		}
		return new PromptCapabilities(or(derived.audio(), returned.audio()),
				or(derived.embeddedContext(), returned.embeddedContext()), or(derived.image(), returned.image()),
				either(returned.meta(), derived.meta()));
	}

	private static @Nullable AgentAuthCapabilities merge(@Nullable AgentAuthCapabilities derived,
			@Nullable AgentAuthCapabilities returned) {
		if (derived == null || returned == null) {
			return (derived != null) ? derived : returned;
		}
		return new AgentAuthCapabilities(either(returned.logout(), derived.logout()),
				either(returned.meta(), derived.meta()));
	}

	private static @Nullable Boolean or(@Nullable Boolean derived, @Nullable Boolean returned) {
		if (derived == null && returned == null) {
			return null;
		}
		return Boolean.TRUE.equals(derived) || Boolean.TRUE.equals(returned);
	}

	/** The first value when not null, otherwise the second. */
	private static <T> @Nullable T either(@Nullable T first, @Nullable T second) {
		return (first != null) ? first : second;
	}

}
