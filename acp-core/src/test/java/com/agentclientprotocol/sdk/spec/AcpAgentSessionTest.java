/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link AcpAgentSession}.
 */
class AcpAgentSessionTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final Duration PROMPT_RESPONSE_DELAY = Duration.ofMillis(250);

	private static final long AGENT_TRANSPORT_SUBSCRIPTION_DELAY_MILLIS = 100;

	private static final long CLIENT_TRANSPORT_SUBSCRIPTION_DELAY_MILLIS = 50;

	private static final int ACTIVE_PROMPT_ERROR_CODE = -32600;

	private static final String SESSION_1 = "session-1";

	private static final String SESSION_2 = "session-2";

	@Test
	void constructorValidatesArguments() {
		var transportPair = InMemoryTransportPair.create();
		try {
			assertThrows(IllegalArgumentException.class,
					() -> new AcpAgentSession(null, transportPair.agentTransport(), Map.of(), Map.of()));
			assertThrows(IllegalArgumentException.class,
					() -> new AcpAgentSession(TIMEOUT, null, Map.of(), Map.of()));
			assertThrows(IllegalArgumentException.class,
					() -> new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), null, Map.of()));
			assertThrows(IllegalArgumentException.class,
					() -> new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Map.of(), null));
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void handlesIncomingRequest() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			// Create session with an initialize handler
			AtomicReference<Object> receivedParams = new AtomicReference<>();
			Map<String, AcpAgentSession.RequestHandler<?>> requestHandlers = Map.of(AcpSchema.METHOD_INITIALIZE,
					params -> {
						receivedParams.set(params);
						return Mono.just(new AcpSchema.InitializeResponse(1,
								new AcpSchema.AgentCapabilities(false, null, null), List.of()));
					});

			new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), requestHandlers, Map.of());

			allowAgentTransportSubscription();

			// Send a request from the client side
			CountDownLatch latch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCMessage> response = new AtomicReference<>();

			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					AcpSchema.METHOD_INITIALIZE, new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()));

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set(msg);
				latch.countDown();
			}).then(Mono.empty())).subscribe();

			allowClientTransportSubscription();
			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse jsonResponse = (AcpSchema.JSONRPCResponse) response.get();
			assertThat(jsonResponse.id()).isEqualTo("1");
			assertThat(jsonResponse.error()).isNull();
			assertThat(receivedParams.get()).isNotNull();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void handlesMethodNotFound() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			// Create session with no handlers
			new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Map.of(), Map.of());

			allowAgentTransportSubscription();

			// Send a request for unknown method
			CountDownLatch latch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCMessage> response = new AtomicReference<>();

			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					"unknown/method", null);

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set(msg);
				latch.countDown();
			}).then(Mono.empty())).subscribe();

			allowClientTransportSubscription();
			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse jsonResponse = (AcpSchema.JSONRPCResponse) response.get();
			assertThat(jsonResponse.error()).isNotNull();
			assertThat(jsonResponse.error().code()).isEqualTo(-32601); // Method not found
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void handlesNotification() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			AtomicReference<Object> receivedParams = new AtomicReference<>();
			CountDownLatch notificationLatch = new CountDownLatch(1);

			Map<String, AcpAgentSession.NotificationHandler> notificationHandlers = Map
				.of(AcpSchema.METHOD_SESSION_CANCEL, params -> {
					receivedParams.set(params);
					notificationLatch.countDown();
					return Mono.empty();
				});

			new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Map.of(), notificationHandlers);

			allowAgentTransportSubscription();

			// Send a notification from client
			AcpSchema.JSONRPCNotification notification = new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
					AcpSchema.METHOD_SESSION_CANCEL, new AcpSchema.CancelNotification(SESSION_1));

			transportPair.clientTransport().connect(mono -> mono.then(Mono.empty())).subscribe();
			allowClientTransportSubscription();
			transportPair.clientTransport().sendMessage(notification).block(TIMEOUT);

			assertThat(notificationLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(receivedParams.get()).isNotNull();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	/** An unhandled notification is logged at WARN by its method only: its params can carry personal data. */
	@Test
	void unhandledNotificationIsLoggedWithoutItsParams() throws Exception {
		Logger sessionLogger = (Logger) LoggerFactory.getLogger(AcpAgentSession.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		sessionLogger.addAppender(logs);
		var transportPair = InMemoryTransportPair.create();
		try {
			new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Map.of(), Map.of());
			allowAgentTransportSubscription();
			transportPair.clientTransport().connect(mono -> mono.then(Mono.empty())).subscribe();
			allowClientTransportSubscription();
			transportPair.clientTransport()
				.sendMessage(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION, "_auth/status_update",
						Map.of("email", "someone@example.com")))
				.block(TIMEOUT);

			long deadline = System.nanoTime() + TIMEOUT.toNanos();
			while (logs.list.stream().noneMatch(event -> event.getLevel() == Level.WARN)
					&& System.nanoTime() < deadline) {
				Thread.sleep(10);
			}
			assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN)
				.extracting(ILoggingEvent::getFormattedMessage)
				.anySatisfy(message -> assertThat(message).contains("_auth/status_update"));
			assertThat(logs.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
				.extracting(ILoggingEvent::getFormattedMessage)
				.allSatisfy(message -> assertThat(message).doesNotContain("someone@example.com"));
		}
		finally {
			sessionLogger.detachAppender(logs);
			logs.stop();
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void singleTurnEnforcementRejectsConcurrentPromptsForSameSession() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			CountDownLatch handlerStarted = new CountDownLatch(1);
			AtomicInteger handlerInvocations = new AtomicInteger();

			Map<String, AcpAgentSession.RequestHandler<?>> requestHandlers = Map.of(AcpSchema.METHOD_SESSION_PROMPT,
					params -> Mono.defer(() -> {
						handlerInvocations.incrementAndGet();
						handlerStarted.countDown();
						return Mono.delay(PROMPT_RESPONSE_DELAY)
							.map(ignored -> new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
					}));

			AcpAgentSession session = new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), requestHandlers,
					Map.of());

			allowAgentTransportSubscription();

			CountDownLatch responseLatch = new CountDownLatch(2);
			List<AcpSchema.JSONRPCResponse> responses = new CopyOnWriteArrayList<>();

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				if (msg instanceof AcpSchema.JSONRPCResponse response) {
					responses.add(response);
				}
				responseLatch.countDown();
			}).then(Mono.empty())).subscribe();

			allowClientTransportSubscription();

			transportPair.clientTransport().sendMessage(promptRequest("1", SESSION_1, "first")).block(TIMEOUT);
			assertThat(handlerStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(session.hasActivePrompt(SESSION_1)).isTrue();

			transportPair.clientTransport().sendMessage(promptRequest("2", SESSION_1, "second")).block(TIMEOUT);

			assertThat(responseLatch.await(5, TimeUnit.SECONDS)).isTrue();

			AcpSchema.JSONRPCResponse rejectedResponse = responseById(responses, "2");
			assertThat(rejectedResponse.error()).isNotNull();
			assertThat(rejectedResponse.error().code()).isEqualTo(ACTIVE_PROMPT_ERROR_CODE);
			assertThat(rejectedResponse.error().message()).contains("already an active prompt");
			assertThat(handlerInvocations.get()).isEqualTo(1);
			awaitPromptCleanup(session);
			assertThat(session.hasActivePrompt()).isFalse();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void singleTurnEnforcementAllowsConcurrentPromptsForDifferentSessions() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			CountDownLatch handlersStarted = new CountDownLatch(2);
			AtomicInteger handlerInvocations = new AtomicInteger();
			Sinks.One<Void> session1Release = Sinks.one();
			Sinks.One<Void> session2Release = Sinks.one();

			Map<String, AcpAgentSession.RequestHandler<?>> requestHandlers = Map.of(AcpSchema.METHOD_SESSION_PROMPT,
					params -> Mono.defer(() -> {
						handlerInvocations.incrementAndGet();
						handlersStarted.countDown();
						String sessionId = sessionId(params);
						Sinks.One<Void> release = SESSION_1.equals(sessionId) ? session1Release : session2Release;
						return release.asMono()
							.thenReturn(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
					}));

			AcpAgentSession session = new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), requestHandlers,
					Map.of());

			allowAgentTransportSubscription();

			CountDownLatch responseLatch = new CountDownLatch(2);
			List<AcpSchema.JSONRPCResponse> responses = new CopyOnWriteArrayList<>();

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				if (msg instanceof AcpSchema.JSONRPCResponse response) {
					responses.add(response);
				}
				responseLatch.countDown();
			}).then(Mono.empty())).subscribe();

			allowClientTransportSubscription();

			transportPair.clientTransport().sendMessage(promptRequest("1", SESSION_1, "first")).block(TIMEOUT);
			transportPair.clientTransport().sendMessage(promptRequest("2", SESSION_2, "second")).block(TIMEOUT);

			assertThat(handlersStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(session.hasActivePrompt(SESSION_1)).isTrue();
			assertThat(session.hasActivePrompt(SESSION_2)).isTrue();
			assertThat(session.getActivePromptSessionIds()).containsExactlyInAnyOrder(SESSION_1, SESSION_2);

			// Release the responses one at a time. The in-memory test transport uses a
			// unicast sink, so simultaneous emissions from concurrent prompt handlers can
			// fail with FAIL_NON_SERIALIZED and obscure the behavior under test.
			session1Release.tryEmitValue(null);
			awaitResponse(responses, "1");
			session2Release.tryEmitValue(null);

			assertThat(responseLatch.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(responseById(responses, "1").error()).isNull();
			assertThat(responseById(responses, "2").error()).isNull();
			assertThat(handlerInvocations.get()).isEqualTo(2);
			awaitPromptCleanup(session);
			assertThat(session.hasActivePrompt()).isFalse();
			assertThat(session.getActivePromptSessionIds()).isEmpty();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void hasActivePromptReturnsCorrectState() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			CountDownLatch handlerStarted = new CountDownLatch(1);

			Map<String, AcpAgentSession.RequestHandler<?>> requestHandlers = Map.of(AcpSchema.METHOD_SESSION_PROMPT,
					params -> Mono.defer(() -> {
						handlerStarted.countDown();
						return Mono.delay(PROMPT_RESPONSE_DELAY)
							.map(ignored -> new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
					}));

			AcpAgentSession session = new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), requestHandlers,
					Map.of());

			allowAgentTransportSubscription();

			assertThat(session.hasActivePrompt()).isFalse();
			assertThat(session.hasActivePrompt(SESSION_1)).isFalse();
			assertThat(session.getActivePromptSessionId()).isNull();
			assertThat(session.getActivePromptSessionIds()).isEmpty();

			CountDownLatch responseLatch = new CountDownLatch(1);
			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> responseLatch.countDown())
				.then(Mono.empty())).subscribe();

			allowClientTransportSubscription();
			transportPair.clientTransport().sendMessage(promptRequest("1", SESSION_1, "hello")).block(TIMEOUT);

			assertThat(handlerStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(session.hasActivePrompt()).isTrue();
			assertThat(session.hasActivePrompt(SESSION_1)).isTrue();
			assertThat(session.getActivePromptSessionIds()).containsExactly(SESSION_1);
			assertThat(session.getActivePromptSessionId()).isEqualTo(SESSION_1);

			assertThat(responseLatch.await(5, TimeUnit.SECONDS)).isTrue();

			awaitPromptCleanup(session);
			assertThat(session.hasActivePrompt()).isFalse();
			assertThat(session.hasActivePrompt(SESSION_1)).isFalse();
			assertThat(session.getActivePromptSessionIds()).isEmpty();
			assertThat(session.getActivePromptSessionId()).isNull();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void closeGracefullyCompletes() throws Exception {
		var transportPair = InMemoryTransportPair.create();

		AcpAgentSession session = new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Map.of(), Map.of());

		allowAgentTransportSubscription();

		// Should complete without error
		session.closeGracefully().block(TIMEOUT);
		transportPair.closeGracefully().block(TIMEOUT);
	}

	@Test
	void handlerErrorReturnsJsonRpcError() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			// Create session with a handler that throws
			Map<String, AcpAgentSession.RequestHandler<?>> requestHandlers = Map.of(AcpSchema.METHOD_INITIALIZE,
					params -> Mono.error(new RuntimeException("Handler error")));

			new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), requestHandlers, Map.of());

			allowAgentTransportSubscription();

			CountDownLatch latch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCMessage> response = new AtomicReference<>();

			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					AcpSchema.METHOD_INITIALIZE, new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()));

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set(msg);
				latch.countDown();
			}).then(Mono.empty())).subscribe();

			allowClientTransportSubscription();
			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse jsonResponse = (AcpSchema.JSONRPCResponse) response.get();
			assertThat(jsonResponse.error()).isNotNull();
			assertThat(jsonResponse.error().code()).isEqualTo(-32603); // Internal error
			assertThat(jsonResponse.error().message()).isEqualTo("Internal error");
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void nullResultForAnEmptyResponseTypeYieldsAnEmptyResponse() throws Exception {
		// The Python SDK client answers fs/write_text_file with "result": null when its handler
		// returns None. Like the Rust SDK, a response type that defaults on null reads it as {}.
		var transportPair = InMemoryTransportPair.create();
		try {
			var session = new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Map.of(), Map.of());
			allowAgentTransportSubscription();
			answerEveryRequestWithNullResult(transportPair);

			AcpSchema.WriteTextFileResponse response = session
				.sendRequest(AcpSchema.METHOD_FS_WRITE_TEXT_FILE, new AcpSchema.WriteTextFileRequest("s", "/f", "x"),
						new TypeRef<AcpSchema.WriteTextFileResponse>() {
						})
				.block(TIMEOUT);

			assertThat(response).isEqualTo(new AcpSchema.WriteTextFileResponse());
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void nullResultForANonEmptyResponseTypeFailsClearly() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			var session = new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Map.of(), Map.of());
			allowAgentTransportSubscription();
			answerEveryRequestWithNullResult(transportPair);

			Mono<AcpSchema.ReadTextFileResponse> response = session.sendRequest(AcpSchema.METHOD_FS_READ_TEXT_FILE,
					new AcpSchema.ReadTextFileRequest("s", "/f", null, null),
					new TypeRef<AcpSchema.ReadTextFileResponse>() {
					});

			StepVerifier.create(response)
				.expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("carried no result"))
				.verify(TIMEOUT);
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	/** A client's {@code {}} must not read as file content whose required content is null. */
	@Test
	void aResultWithoutARequiredFieldFailsTheRequest() throws Exception {
		var transportPair = InMemoryTransportPair.create();
		try {
			var session = new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Map.of(), Map.of());
			allowAgentTransportSubscription();
			answerEveryRequestWith(transportPair, Map.of());

			Mono<AcpSchema.ReadTextFileResponse> response = session.sendRequest(AcpSchema.METHOD_FS_READ_TEXT_FILE,
					new AcpSchema.ReadTextFileRequest("s", "/f", null, null),
					new TypeRef<AcpSchema.ReadTextFileResponse>() {
					});

			StepVerifier.create(response)
				.expectErrorSatisfies(error -> assertThat(error)
					.isInstanceOfSatisfying(AcpError.class,
							e -> assertThat(e.getCode()).isEqualTo(-32603))
					.hasMessageContaining("The response to fs/read_text_file lacks the required field content"))
				.verify(TIMEOUT);
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void nullResultForAnExtensionMethodCompletesEmpty() throws Exception {
		// An extension method's result is free-form, and null is a legal value of it.
		var transportPair = InMemoryTransportPair.create();
		try {
			var session = new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Map.of(), Map.of());
			allowAgentTransportSubscription();
			answerEveryRequestWithNullResult(transportPair);

			Mono<Object> response = session.sendRequest("_vendor/ping", Map.of(), new TypeRef<Object>() {
			});

			StepVerifier.create(response).verifyComplete();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	private static void answerEveryRequestWithNullResult(InMemoryTransportPair transportPair)
			throws InterruptedException {
		answerEveryRequestWith(transportPair, null);
	}

	private static void answerEveryRequestWith(InMemoryTransportPair transportPair, Object result)
			throws InterruptedException {
		var clientTransport = transportPair.clientTransport();
		clientTransport.connect(mono -> mono.flatMap(msg -> {
			if (msg instanceof AcpSchema.JSONRPCRequest request) {
				return clientTransport
					.sendMessage(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), result, null))
					.then(Mono.<AcpSchema.JSONRPCMessage>empty());
			}
			return Mono.<AcpSchema.JSONRPCMessage>empty();
		})).subscribe();
		allowClientTransportSubscription();
	}

	private static AcpSchema.JSONRPCRequest promptRequest(String id, String sessionId, String text) {
		return new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, id, AcpSchema.METHOD_SESSION_PROMPT,
				new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent(text))));
	}

	private static AcpSchema.JSONRPCResponse responseById(List<AcpSchema.JSONRPCResponse> responses, Object id) {
		return responses.stream().filter(response -> id.equals(response.id())).findFirst().orElseThrow();
	}

	private static void awaitPromptCleanup(AcpAgentSession session) {
		// A response is emitted before doFinally removes the completed prompt.
		Mono.delay(Duration.ofMillis(10))
			.repeat()
			.filter(ignored -> !session.hasActivePrompt())
			.next()
			.block(TIMEOUT);
	}

	private static void awaitResponse(List<AcpSchema.JSONRPCResponse> responses, Object id) throws InterruptedException {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			if (responses.stream().anyMatch(response -> id.equals(response.id()))) {
				return;
			}
			Thread.sleep(10);
		}
	}

	private static String sessionId(Object params) {
		if (params instanceof AcpSchema.PromptRequest promptRequest) {
			return promptRequest.sessionId();
		}
		throw new IllegalArgumentException("Expected PromptRequest params but received " + params);
	}

	private static void allowAgentTransportSubscription() throws InterruptedException {
		// AcpAgentSession subscribes to the in-memory transport in its constructor.
		// subscribe() is asynchronous, so give the unicast sink subscriber a short
		// window to attach before the test sends client messages.
		Thread.sleep(AGENT_TRANSPORT_SUBSCRIPTION_DELAY_MILLIS);
	}

	private static void allowClientTransportSubscription() throws InterruptedException {
		// clientTransport.connect(...).subscribe() also attaches asynchronously. Without
		// this small wait, an immediate agent response can race the test subscriber.
		Thread.sleep(CLIENT_TRANSPORT_SUBSCRIPTION_DELAY_MILLIS);
	}

}
