/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for logging behavior in {@link AcpClientSession}.
 *
 * <p>These tests verify that:
 * <ul>
 * <li>WARN is logged when an unhandled request method is received</li>
 * <li>WARN contains actionable information (method name, suggestion)</li>
 * <li>DEBUG is logged for handler invocations</li>
 * </ul>
 *
 * @author Mark Pollack
 */
class AcpClientSessionLogTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private ListAppender<ILoggingEvent> listAppender;

	private Logger sessionLogger;

	@BeforeEach
	void setUp() {
		// Get the logger for AcpClientSession
		sessionLogger = (Logger) LoggerFactory.getLogger(AcpClientSession.class);

		// Create and start a ListAppender to capture log events
		listAppender = new ListAppender<>();
		listAppender.start();

		// Add the appender to the logger
		sessionLogger.addAppender(listAppender);

		// Ensure we capture all levels
		sessionLogger.setLevel(Level.TRACE);
	}

	@AfterEach
	void tearDown() {
		// Remove the appender after test
		sessionLogger.detachAppender(listAppender);
		listAppender.stop();
	}

	/**
	 * Verifies that a WARN log is produced when an unhandled request method is received.
	 */
	@Test
	void warnLogWhenNoHandlerForRequestMethod() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();

		// Create session with no handlers
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		// Simulate incoming request for unhandled method
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				"unknown/method",
				Map.of("key", "value")
		);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		Thread.sleep(200);

		// Verify WARN was logged
		List<ILoggingEvent> warnLogs = listAppender.list.stream()
			.filter(event -> event.getLevel() == Level.WARN)
			.filter(event -> event.getMessage().contains("No handler registered"))
			.toList();

		assertThat(warnLogs)
			.describedAs("Expected WARN log when no handler registered for method")
			.hasSize(1);

		ILoggingEvent warnLog = warnLogs.get(0);
		assertThat(warnLog.getFormattedMessage())
			.contains("unknown/method")
			.contains("register a handler");

		session.close();
	}

	/**
	 * Verifies that specific WARN message is logged for session/request_permission method.
	 */
	@Test
	void warnLogForRequestPermissionContainsYoloSuggestion() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();

		// Create session with no handlers
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		// Simulate incoming permission request
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				AcpSchema.METHOD_SESSION_REQUEST_PERMISSION,
				Map.of("sessionId", "session-123", "toolCall", Map.of("title", "Test"))
		);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		Thread.sleep(200);

		// Verify WARN contains --yolo suggestion
		List<ILoggingEvent> warnLogs = listAppender.list.stream()
			.filter(event -> event.getLevel() == Level.WARN)
			.filter(event -> event.getMessage().contains("No handler registered"))
			.toList();

		assertThat(warnLogs).hasSize(1);
		assertThat(warnLogs.get(0).getFormattedMessage())
			.contains("session/request_permission")
			.contains("--yolo");

		session.close();
	}

	/**
	 * Verifies that WARN message for fs/read_text_file mentions capability.
	 */
	@Test
	void warnLogForReadTextFileMentionsCapability() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();

		// Create session with no handlers
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		// Simulate incoming read request
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				AcpSchema.METHOD_FS_READ_TEXT_FILE,
				Map.of("path", "/test.txt")
		);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		Thread.sleep(200);

		// Verify WARN mentions capability
		List<ILoggingEvent> warnLogs = listAppender.list.stream()
			.filter(event -> event.getLevel() == Level.WARN)
			.toList();

		assertThat(warnLogs).isNotEmpty();
		assertThat(warnLogs.get(0).getFormattedMessage())
			.contains("fs/read_text_file")
			.containsIgnoringCase("capability");

		session.close();
	}

	/** Personal data an agent can send: _auth/status_update carries the account's email. */
	private static final String EMAIL = "someone@example.com";

	/**
	 * An unhandled notification is logged at WARN with its method only: its params can carry
	 * personal data. Claude's and Codex's agents send {@code _auth/status_update}, whose
	 * params name the account's email address in login mode.
	 */
	@Test
	void unhandledNotificationIsLoggedWithoutItsParams() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		transport.simulateIncomingMessage(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				"_auth/status_update", Map.of("status", "logged_in", "email", EMAIL)));
		session.closeGracefully().block(TIMEOUT);

		assertThat(listAppender.list).filteredOn(event -> event.getLevel() == Level.WARN)
			.extracting(ILoggingEvent::getFormattedMessage)
			.anySatisfy(message -> assertThat(message).contains("No handler registered")
				.contains("_auth/status_update"));
		assertNoPersonalDataAboveDebug(listAppender.list);
	}

	/**
	 * An error response fails the caller's request, which is the report: the session logs
	 * it at DEBUG only, and never its data, which is the peer's payload.
	 */
	@Test
	void errorResponseIsLoggedWithoutItsData() {
		Logger outboundLogger = (Logger) LoggerFactory.getLogger(OutboundMessages.class);
		ListAppender<ILoggingEvent> outboundLogs = new ListAppender<>();
		outboundLogs.start();
		outboundLogger.addAppender(outboundLogs);
		try {
			MockAcpClientTransport transport = new MockAcpClientTransport();
			AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(),
					Function.identity());
			var response = session.sendRequest("test/method", Map.of(), new TypeRef<Object>() {
			}).toFuture();
			AcpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			transport.simulateIncomingMessage(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(),
					null, new AcpSchema.JSONRPCError(-32000, "Authentication required", Map.of("email", EMAIL))));

			assertThat(response).failsWithin(TIMEOUT);
			assertThat(outboundLogs.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO)).isEmpty();
			assertThat(outboundLogs.list).filteredOn(event -> event.getLevel() == Level.DEBUG)
				.extracting(ILoggingEvent::getFormattedMessage)
				.anySatisfy(message -> assertThat(message).contains("-32000"))
				.allSatisfy(message -> assertThat(message).doesNotContain(EMAIL));
			assertNoPersonalDataAboveDebug(outboundLogs.list);
			session.close();
		}
		finally {
			outboundLogger.detachAppender(outboundLogs);
			outboundLogs.stop();
		}
	}

	private static void assertNoPersonalDataAboveDebug(List<ILoggingEvent> events) {
		assertThat(events).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
			.extracting(ILoggingEvent::getFormattedMessage)
			.allSatisfy(message -> assertThat(message).doesNotContain(EMAIL));
	}

	/**
	 * Verifies that DEBUG log is produced when a handler is invoked.
	 */
	@Test
	void debugLogWhenHandlerInvoked() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();

		// Create session with a handler
		Map<String, AcpClientSession.RequestHandler<?>> handlers = Map.of(
				"test/method",
				params -> reactor.core.publisher.Mono.just("response")
		);

		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, handlers, Map.of(), Function.identity());

		// Simulate incoming request
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				"test/method",
				Map.of("key", "value")
		);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		Thread.sleep(200);

		// Verify DEBUG was logged for handler invocation
		List<ILoggingEvent> debugLogs = listAppender.list.stream()
			.filter(event -> event.getLevel() == Level.DEBUG)
			.filter(event -> event.getMessage().contains("Invoking handler"))
			.toList();

		assertThat(debugLogs)
			.describedAs("Expected DEBUG log when handler invoked")
			.hasSize(1);

		assertThat(debugLogs.get(0).getFormattedMessage())
			.contains("test/method");

		session.close();
	}

	/**
	 * Verifies that DEBUG log is produced when handler completes successfully.
	 */
	@Test
	void debugLogWhenHandlerCompletesSuccessfully() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();

		// Create session with a handler
		Map<String, AcpClientSession.RequestHandler<?>> handlers = Map.of(
				"test/method",
				params -> reactor.core.publisher.Mono.just("response")
		);

		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, handlers, Map.of(), Function.identity());

		// Simulate incoming request
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				"test/method",
				null
		);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		Thread.sleep(200);

		// Verify DEBUG was logged for handler completion
		List<ILoggingEvent> debugLogs = listAppender.list.stream()
			.filter(event -> event.getLevel() == Level.DEBUG)
			.filter(event -> event.getMessage().contains("completed successfully"))
			.toList();

		assertThat(debugLogs)
			.describedAs("Expected DEBUG log when handler completes successfully")
			.hasSize(1);

		session.close();
	}

	/**
	 * Verifies that available handlers are logged at TRACE level when method not found.
	 */
	@Test
	void traceLogShowsAvailableHandlers() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();

		// Create session with some handlers
		Map<String, AcpClientSession.RequestHandler<?>> handlers = Map.of(
				"registered/method1", params -> reactor.core.publisher.Mono.just("r1"),
				"registered/method2", params -> reactor.core.publisher.Mono.just("r2")
		);

		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, handlers, Map.of(), Function.identity());

		// Simulate incoming request for unknown method
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				"unknown/method",
				null
		);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		Thread.sleep(200);

		// Verify TRACE shows available handlers
		List<ILoggingEvent> traceLogs = listAppender.list.stream()
			.filter(event -> event.getLevel() == Level.TRACE)
			.filter(event -> event.getMessage().contains("Available handlers"))
			.toList();

		assertThat(traceLogs)
			.describedAs("Expected TRACE log showing available handlers")
			.hasSize(1);

		String traceMessage = traceLogs.get(0).getFormattedMessage();
		assertThat(traceMessage)
			.contains("registered/method1")
			.contains("registered/method2");

		session.close();
	}

}
