/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every client-to-agent method of ACP v1, stable and unstable (schema/v1/meta.unstable.json),
 * is routed from the table in {@link StreamableHttpRoutes}: connection-scoped methods carry
 * no {@code Acp-Session-Id}, session-scoped ones carry the session their params name, and
 * none falls back to inferring its scope from its params.
 */
class StreamableHttpRoutesTest {

	private static final String SESSION = "sess-1";

	private ListAppender<ILoggingEvent> appender;

	private Logger logger;

	@BeforeEach
	void captureLogs() {
		this.logger = (Logger) LoggerFactory.getLogger(StreamableHttpRoutes.class);
		this.appender = new ListAppender<>();
		this.appender.start();
		this.logger.addAppender(this.appender);
	}

	@AfterEach
	void releaseLogs() {
		this.logger.detachAppender(this.appender);
		this.appender.stop();
	}

	static Stream<Arguments> connectionScopedMethods() {
		return Stream.of("authenticate", "logout", "session/new", "session/list", "providers/list", "providers/set",
				"providers/disable", "nes/start", "mcp/message", "$/cancel_request")
			.map(Arguments::of);
	}

	static Stream<Arguments> sessionScopedMethods() {
		return Stream.of("session/prompt", "session/cancel", "session/set_mode", "session/set_config_option",
				"session/close", "session/delete", "session/fork", "session/load", "session/resume", "nes/suggest",
				"nes/accept", "nes/reject", "nes/close", "document/didOpen", "document/didChange", "document/didClose",
				"document/didSave", "document/didFocus")
			.map(Arguments::of);
	}

	@ParameterizedTest
	@MethodSource("connectionScopedMethods")
	void connectionScopedMethodIsPostedOnTheConnection(String method) {
		StreamableHttpRoutes routes = new StreamableHttpRoutes(AcpJsonMapper.createDefault());

		RouteScope scope = routes.resolveOutbound(new AcpSchema.JSONRPCRequest(method, 1, Map.of("cwd", "/w")));

		assertThat(scope).isEqualTo(RouteScope.connection());
		assertNoFallback();
	}

	@ParameterizedTest
	@MethodSource("sessionScopedMethods")
	void sessionScopedMethodIsPostedInItsSession(String method) {
		StreamableHttpRoutes routes = new StreamableHttpRoutes(AcpJsonMapper.createDefault());

		RouteScope scope = routes.resolveOutbound(new AcpSchema.JSONRPCRequest(method, 1, Map.of("sessionId", SESSION)));

		assertThat(scope).isEqualTo(RouteScope.session(SESSION));
		assertNoFallback();
	}

	@ParameterizedTest
	@MethodSource("sessionScopedMethods")
	void sessionScopedMethodWithoutASessionIdIsRefused(String method) {
		StreamableHttpRoutes routes = new StreamableHttpRoutes(AcpJsonMapper.createDefault());

		assertThatThrownBy(() -> routes.resolveOutbound(new AcpSchema.JSONRPCRequest(method, 1, Map.of())))
			.isInstanceOf(AcpConnectionException.class)
			.hasMessageContaining("Missing sessionId");
	}

	/** The table names every method AcpSchema defines for the client to call. */
	@Test
	void everyAgentMethodConstantHasARoute() {
		List<String> agentMethods = List.of(AcpSchema.METHOD_INITIALIZE, AcpSchema.METHOD_AUTHENTICATE,
				AcpSchema.METHOD_LOGOUT, AcpSchema.METHOD_SESSION_NEW, AcpSchema.METHOD_SESSION_LOAD,
				AcpSchema.METHOD_SESSION_PROMPT, AcpSchema.METHOD_SESSION_SET_MODE, AcpSchema.METHOD_SESSION_CANCEL,
				AcpSchema.METHOD_SESSION_LIST, AcpSchema.METHOD_SESSION_CLOSE, AcpSchema.METHOD_SESSION_DELETE,
				AcpSchema.METHOD_SESSION_RESUME, AcpSchema.METHOD_SESSION_FORK,
				AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION, AcpSchema.METHOD_PROVIDERS_LIST,
				AcpSchema.METHOD_PROVIDERS_SET, AcpSchema.METHOD_PROVIDERS_DISABLE);

		assertThat(StreamableHttpRoutes.routedMethods()).containsAll(agentMethods);
	}

	/** The load family answers on the connection stream: the client has no session stream yet. */
	@Test
	void loadAndResumeAreAnsweredOnTheConnectionStream() {
		StreamableHttpRoutes routes = new StreamableHttpRoutes(AcpJsonMapper.createDefault());
		routes.resolveOutbound(new AcpSchema.JSONRPCRequest(AcpSchema.METHOD_SESSION_LOAD, 1, Map.of("sessionId", SESSION)));
		routes.resolveOutbound(new AcpSchema.JSONRPCRequest(AcpSchema.METHOD_SESSION_DELETE, 2, Map.of("sessionId", SESSION)));

		assertThat(routes.hasPendingResponseFor(RouteScope.connection())).isTrue();
		routes.answered(1);
		assertThat(routes.hasPendingResponseFor(RouteScope.connection())).isFalse();
		assertThat(routes.hasPendingResponseFor(RouteScope.session(SESSION))).isTrue();
	}

	/** An extension method has no table entry; it is still routed, by inference. */
	@Test
	void anExtensionMethodIsRoutedByItsParams() {
		StreamableHttpRoutes routes = new StreamableHttpRoutes(AcpJsonMapper.createDefault());

		assertThat(routes.resolveOutbound(new AcpSchema.JSONRPCRequest("_vendor/ping", 1, Map.of("sessionId", SESSION))))
			.isEqualTo(RouteScope.session(SESSION));
		assertThat(routes.resolveOutbound(new AcpSchema.JSONRPCRequest("_vendor/ping", 2, Map.of())))
			.isEqualTo(RouteScope.connection());
	}

	/**
	 * $/cancel_request is protocol-level: posted on the connection, without the session
	 * header, even while the prompt it cancels was posted in its session (the Rust SDK's rule).
	 */
	@Test
	void aCancelRequestIsPostedOnTheConnectionEvenForASessionRequest() {
		StreamableHttpRoutes routes = new StreamableHttpRoutes(AcpJsonMapper.createDefault());
		routes.resolveOutbound(new AcpSchema.JSONRPCRequest(AcpSchema.METHOD_SESSION_PROMPT, 7,
				Map.of("sessionId", SESSION, "prompt", java.util.List.of())));

		RouteScope scope = routes.resolveOutbound(new AcpSchema.JSONRPCNotification(AcpSchema.METHOD_CANCEL_REQUEST,
				new AcpSchema.CancelRequestNotification(7)));

		assertThat(scope).isEqualTo(RouteScope.connection());
		assertNoFallback();
	}

	private void assertNoFallback() {
		assertThat(this.appender.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
			.extracting(ILoggingEvent::getFormattedMessage)
			.isEmpty();
	}

}
