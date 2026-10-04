/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.Map;
import java.util.Set;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;

/**
 * The {@code initialize} answer of a builder agent without an initialize handler: the protocol
 * version negotiated with the client, and the capabilities its registered handlers imply
 * ({@code session/load} advertises {@code loadSession}, {@code session/list} advertises
 * {@code sessionCapabilities.list}, {@code logout} advertises {@code auth.logout}, any of the three
 * {@code providers/*} methods advertises {@code providers}, and so on),
 * as an annotated agent derives them from its annotations.
 */
final class DefaultInitialize {

	private static final Map<String, Object> SUPPORTED = Map.of();

	private DefaultInitialize() {
	}

	/**
	 * The default response for an agent serving {@code methods}.
	 * @param methods the request methods the agent has handlers for
	 * @param agentInfo the agent's name and version, or null to send none
	 * @param request the client's initialize request
	 * @return the response
	 */
	static AcpSchema.InitializeResponse respond(Set<String> methods, AcpSchema.@Nullable Implementation agentInfo,
			AcpSchema.InitializeRequest request) {
		boolean anySession = methods.contains(AcpSchema.METHOD_SESSION_LIST)
				|| methods.contains(AcpSchema.METHOD_SESSION_CLOSE) || methods.contains(AcpSchema.METHOD_SESSION_RESUME)
				|| methods.contains(AcpSchema.METHOD_SESSION_DELETE) || methods.contains(AcpSchema.METHOD_SESSION_FORK);
		AcpSchema.SessionCapabilities sessions = anySession ? new AcpSchema.SessionCapabilities(
				supported(methods, AcpSchema.METHOD_SESSION_LIST), supported(methods, AcpSchema.METHOD_SESSION_CLOSE),
				supported(methods, AcpSchema.METHOD_SESSION_RESUME), supported(methods, AcpSchema.METHOD_SESSION_DELETE),
				null, supported(methods, AcpSchema.METHOD_SESSION_FORK)) : null;
		AcpSchema.AgentCapabilities capabilities = AcpSchema.AgentCapabilities.builder()
			.loadSession(methods.contains(AcpSchema.METHOD_SESSION_LOAD))
			.sessionCapabilities(sessions)
			.auth(methods.contains(AcpSchema.METHOD_LOGOUT) ? AcpSchema.AgentAuthCapabilities.withLogout() : null)
			.providers(methods.contains(AcpSchema.METHOD_PROVIDERS_LIST)
					|| methods.contains(AcpSchema.METHOD_PROVIDERS_SET)
					|| methods.contains(AcpSchema.METHOD_PROVIDERS_DISABLE) ? new AcpSchema.ProvidersCapabilities(null)
							: null)
			.build();
		return new AcpSchema.InitializeResponse(negotiate(request.protocolVersion()), capabilities, null, agentInfo,
				null);
	}

	/** The client's version when this SDK speaks it, otherwise the latest it speaks. */
	static int negotiate(@Nullable Integer requested) {
		return (requested != null && requested == AcpSchema.LATEST_PROTOCOL_VERSION) ? requested
				: AcpSchema.LATEST_PROTOCOL_VERSION;
	}

	private static @Nullable Object supported(Set<String> methods, String method) {
		return methods.contains(method) ? SUPPORTED : null;
	}

}
