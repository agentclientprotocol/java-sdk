/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.http.server.StreamableHttpRouting.ClientRequestRoute;
import com.agentclientprotocol.sdk.http.server.StreamableHttpRouting.RouteScope;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The server routes every client-to-agent method of ACP v1, stable and unstable, from its
 * table: connection-scoped methods on the connection, session-scoped methods only with an
 * {@code Acp-Session-Id} matching their params (the RFD: "Session-scoped request missing
 * Acp-Session-Id? -> 400 Bad Request").
 */
class StreamableHttpRoutingTest {

	private static final String SESSION = "sess-1";

	private final StreamableHttpRouting routing = new StreamableHttpRouting(AcpJsonMapper.createDefault());

	static Stream<Arguments> connectionScopedMethods() {
		return Stream.of("authenticate", "logout", "session/new", "session/list", "providers/list", "providers/set",
				"providers/disable", "nes/start", "mcp/message", "$/cancel_request")
			.map(Arguments::of);
	}

	static Stream<Arguments> sessionScopedMethods() {
		return Stream.of("session/prompt", "session/cancel", "session/set_mode", "session/set_config_option",
				"session/close", "session/delete", "session/fork", "nes/suggest", "nes/accept", "nes/reject",
				"nes/close", "document/didOpen", "document/didChange", "document/didClose", "document/didSave",
				"document/didFocus")
			.map(Arguments::of);
	}

	@ParameterizedTest
	@MethodSource("connectionScopedMethods")
	void connectionScopedMethodIsRoutedOnTheConnection(String method) {
		ClientRequestRoute route = routing
			.resolveInboundRoute(new AcpSchema.JSONRPCRequest(method, 1, Map.of("cwd", "/w")), null)
			.requestRoute();

		assertThat(route.requestScope()).isEqualTo(RouteScope.connection());
		assertThat(route.responseScope()).isEqualTo(RouteScope.connection());
	}

	/**
	 * The agent sends $/cancel_request on the connection stream, even when its params name a
	 * session, matching the client's table and the Rust SDK.
	 */
	@Test
	void anAgentCancelRequestGoesOnTheConnectionStream() {
		assertThat(routing.resolveAgentRequestOrNotificationScope(AcpSchema.METHOD_CANCEL_REQUEST,
				Map.of("requestId", 3, "sessionId", SESSION)))
			.isEqualTo(RouteScope.connection());
	}

	@ParameterizedTest
	@MethodSource("sessionScopedMethods")
	void sessionScopedMethodIsRoutedInItsSession(String method) {
		ClientRequestRoute route = routing
			.resolveInboundRoute(new AcpSchema.JSONRPCRequest(method, 1, Map.of("sessionId", SESSION)), SESSION)
			.requestRoute();

		assertThat(route.requestScope()).isEqualTo(RouteScope.session(SESSION));
		assertThat(route.responseScope()).isEqualTo(RouteScope.session(SESSION));
	}

	static Stream<Arguments> headerRequiredMethods() {
		return sessionScopedMethods().filter(arguments -> !List.of("session/delete", "session/fork")
			.contains(arguments.get()[0]));
	}

	/** delete and fork, like load, are accepted without the header and answered on the connection. */
	@Test
	void deleteAndForkWithoutTheSessionHeaderAreAnsweredOnTheConnection() {
		for (String method : List.of(AcpSchema.METHOD_SESSION_DELETE, AcpSchema.METHOD_SESSION_FORK)) {
			ClientRequestRoute route = routing
				.resolveInboundRoute(new AcpSchema.JSONRPCRequest(method, 1, Map.of("sessionId", SESSION)), null)
				.requestRoute();
			assertThat(route.requestScope()).isEqualTo(RouteScope.session(SESSION));
			assertThat(route.responseScope()).isEqualTo(RouteScope.connection());
		}
	}

	@ParameterizedTest
	@MethodSource("headerRequiredMethods")
	void sessionScopedMethodWithoutTheSessionHeaderIsRefused(String method) {
		assertThatThrownBy(() -> routing
			.resolveInboundRoute(new AcpSchema.JSONRPCRequest(method, 1, Map.of("sessionId", SESSION)), null))
			.isInstanceOf(AcpConnectionException.class)
			.hasMessageContaining("Acp-Session-Id header required");
	}

	@ParameterizedTest
	@MethodSource("sessionScopedMethods")
	void sessionScopedMethodWithoutASessionIdIsRefused(String method) {
		assertThatThrownBy(
				() -> routing.resolveInboundRoute(new AcpSchema.JSONRPCRequest(method, 1, Map.of()), SESSION))
			.isInstanceOf(AcpConnectionException.class)
			.hasMessageContaining("Missing sessionId");
	}

	@Test
	void loadAndResumeAreAnsweredOnTheConnectionStream() {
		for (String method : List.of(AcpSchema.METHOD_SESSION_LOAD, AcpSchema.METHOD_SESSION_RESUME)) {
			ClientRequestRoute route = routing
				.resolveInboundRoute(new AcpSchema.JSONRPCRequest(method, 1, Map.of("sessionId", SESSION)), null)
				.requestRoute();
			assertThat(route.requestScope()).isEqualTo(RouteScope.session(SESSION));
			assertThat(route.responseScope()).isEqualTo(RouteScope.connection());
		}
	}

	/** The table names every method AcpSchema defines for the client to call, but initialize. */
	@Test
	void everyAgentMethodConstantHasARule() {
		assertThat(routing.routedMethods()).contains(AcpSchema.METHOD_AUTHENTICATE, AcpSchema.METHOD_LOGOUT,
				AcpSchema.METHOD_SESSION_NEW, AcpSchema.METHOD_SESSION_LOAD, AcpSchema.METHOD_SESSION_PROMPT,
				AcpSchema.METHOD_SESSION_SET_MODE, AcpSchema.METHOD_SESSION_CANCEL, AcpSchema.METHOD_SESSION_LIST,
				AcpSchema.METHOD_SESSION_CLOSE, AcpSchema.METHOD_SESSION_DELETE, AcpSchema.METHOD_SESSION_RESUME,
				AcpSchema.METHOD_SESSION_FORK, AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION,
				AcpSchema.METHOD_PROVIDERS_LIST, AcpSchema.METHOD_PROVIDERS_SET, AcpSchema.METHOD_PROVIDERS_DISABLE);
	}

}
