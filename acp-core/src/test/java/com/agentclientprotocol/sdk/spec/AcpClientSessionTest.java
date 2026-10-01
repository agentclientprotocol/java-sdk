/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite for {@link AcpClientSession} that verifies its JSON-RPC message handling,
 * request-response correlation, and notification processing.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
class AcpClientSessionTest {

	private static final Logger logger = LoggerFactory.getLogger(AcpClientSessionTest.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String TEST_METHOD = "test.method";

	private static final String TEST_NOTIFICATION = "test.notification";

	private static final String ECHO_METHOD = "echo";

	TypeRef<String> responseType = new TypeRef<>() {
	};

	@Test
	void testSendRequest() {
		String testParam = "test parameter";
		String responseData = "test response";

		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		// Create a Mono that will emit the response after the request is sent
		Mono<String> responseMono = session.sendRequest(TEST_METHOD, testParam, responseType);

		// Verify response handling
		StepVerifier.create(responseMono).then(() -> {
			AcpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			transport.simulateIncomingMessage(
					new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), responseData, null));
		}).consumeNextWith(response -> {
			// Verify the request was sent
			AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessageAsRequest();
			assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCRequest.class);
			AcpSchema.JSONRPCRequest request = (AcpSchema.JSONRPCRequest) sentMessage;
			assertThat(request.method()).isEqualTo(TEST_METHOD);
			assertThat(request.params()).isEqualTo(testParam);
			assertThat(response).isEqualTo(responseData);
		}).verifyComplete();

		session.close();
	}

	@Test
	void testSendRequestWithError() {
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		Mono<String> responseMono = session.sendRequest(TEST_METHOD, "test", responseType);

		// Verify error handling
		StepVerifier.create(responseMono).then(() -> {
			AcpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			// Simulate error response
			AcpSchema.JSONRPCError error = new AcpSchema.JSONRPCError(-32601, "Method not found", null);
			transport.simulateIncomingMessage(
					new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null, error));
		}).expectError(AcpError.class).verify();

		session.close();
	}

	@Test
	void testRequestTimeout() {
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		Mono<String> responseMono = session.sendRequest(TEST_METHOD, "test", responseType);

		// Verify timeout
		StepVerifier.create(responseMono)
			.expectError(java.util.concurrent.TimeoutException.class)
			.verify(TIMEOUT.plusSeconds(1));

		session.close();
	}

	@Test
	void testSendNotification() {
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		Map<String, Object> params = Map.of("key", "value");
		Mono<Void> notificationMono = session.sendNotification(TEST_NOTIFICATION, params);

		// Verify notification was sent
		StepVerifier.create(notificationMono).consumeSubscriptionWith(response -> {
			AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
			assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCNotification.class);
			AcpSchema.JSONRPCNotification notification = (AcpSchema.JSONRPCNotification) sentMessage;
			assertThat(notification.method()).isEqualTo(TEST_NOTIFICATION);
			assertThat(notification.params()).isEqualTo(params);
		}).verifyComplete();

		session.close();
	}

	@Test
	void testRequestHandling() {
		String echoMessage = "Hello ACP!";
		Map<String, AcpClientSession.RequestHandler<?>> requestHandlers = Map.of(ECHO_METHOD,
				params -> Mono.just(params));
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, requestHandlers, Map.of(), Function.identity());

		// Simulate incoming request
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "test-id",
				ECHO_METHOD, echoMessage);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Verify response
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.result()).isEqualTo(echoMessage);
		assertThat(response.error()).isNull();

		session.close();
	}

	@Test
	void testNotificationHandling() {
		Sinks.One<Object> receivedParams = Sinks.one();

		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> receivedParams.tryEmitValue(params))),
				Function.identity());

		// Simulate incoming notification from the agent
		Map<String, Object> notificationParams = Map.of("status", "ready");

		AcpSchema.JSONRPCNotification notification = new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				TEST_NOTIFICATION, notificationParams);

		transport.simulateIncomingMessage(notification);

		// Verify handler was called
		assertThat(receivedParams.asMono().block(Duration.ofSeconds(1))).isEqualTo(notificationParams);

		session.close();
	}

	@Test
	void testUnknownMethodHandling() {
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		// Simulate incoming request for unknown method
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "test-id",
				"unknown.method", null);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Verify error response
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(-32601);

		session.close();
	}

	@Test
	void testRequestHandlerThrowsRuntimeException() {
		// Setup: Create a request handler that throws a generic RuntimeException
		String testMethod = "test.genericError";
		RuntimeException exception = new RuntimeException("Something went wrong");
		AcpClientSession.RequestHandler<?> failingHandler = params -> Mono.error(exception);

		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(testMethod, failingHandler), Map.of(),
				Function.identity());

		// Simulate incoming request that will trigger the error
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "test-id",
				testMethod, null);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Verify: The response should contain INTERNAL_ERROR
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(-32603);
		assertThat(response.error().message()).isEqualTo("Something went wrong");

		session.close();
	}

	@Test
	void testRequestHandlerThrowsExceptionWithCause() {
		// Setup: Create a request handler that throws an exception with a cause chain
		String testMethod = "test.chainedError";
		RuntimeException rootCause = new IllegalArgumentException("Root cause message");
		RuntimeException middleCause = new IllegalStateException("Middle cause message", rootCause);
		RuntimeException topException = new RuntimeException("Top level message", middleCause);
		AcpClientSession.RequestHandler<?> failingHandler = params -> Mono.error(topException);

		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(testMethod, failingHandler), Map.of(),
				Function.identity());

		// Simulate incoming request that will trigger the error
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "test-id",
				testMethod, null);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Verify: The response should contain INTERNAL_ERROR with exception message
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(-32603);
		assertThat(response.error().message()).isEqualTo("Top level message");

		session.close();
	}

	@Test
	void testGracefulShutdown() {
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		StepVerifier.create(session.closeGracefully()).verifyComplete();
	}

	@Test
	void testNotificationOrderPreservedWithAsyncHandler() throws InterruptedException {
		// Notification i gets a delay of (5 - i) * 30ms so that without serialization
		// later notifications would complete first, reversing the observed order.
		int count = 5;
		List<Integer> processedOrder = new CopyOnWriteArrayList<>();
		CountDownLatch latch = new CountDownLatch(count);

		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> {
					int index = (int) ((Map<?, ?>) params).get("index");
					long delayMs = (count - index) * 30L;
					return Mono.delay(Duration.ofMillis(delayMs)).then(Mono.fromRunnable(() -> {
						processedOrder.add(index);
						latch.countDown();
					}));
				}),
				Function.identity());

		for (int i = 0; i < count; i++) {
			transport.simulateIncomingMessage(new AcpSchema.JSONRPCNotification(
					AcpSchema.JSONRPC_VERSION, TEST_NOTIFICATION, Map.of("index", i)));
		}

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(processedOrder).containsExactly(0, 1, 2, 3, 4);

		session.close();
	}

	@Test
	void testGracefulCloseDrainsQueuedNotifications() {
		// Notifications queued behind an async handler must be delivered by a graceful
		// close, not discarded when the drain subscription is disposed.
		int count = 10;
		List<Integer> processedOrder = new CopyOnWriteArrayList<>();

		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> {
					int index = (int) ((Map<?, ?>) params).get("index");
					return Mono.delay(Duration.ofMillis(20)).doOnNext(t -> processedOrder.add(index)).then();
				}),
				Function.identity());

		for (int i = 0; i < count; i++) {
			transport.simulateIncomingMessage(new AcpSchema.JSONRPCNotification(
					AcpSchema.JSONRPC_VERSION, TEST_NOTIFICATION, Map.of("index", i)));
		}

		session.closeGracefully().block();

		assertThat(processedOrder).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
	}

	@Test
	void gracefulCloseWhileANotificationIsBeingDeliveredDoesNotWaitOutTheRequestTimeout() throws Exception {
		// The live case: an agent sends a notification just as the client closes after a turn.
		// A handler that does its work synchronously runs on the inbound thread inside the
		// notification sink's emission, so the close's completion of that sink collides with
		// it. The completion must still happen: the close then waits only for the handler,
		// not for the request timeout. The handler outlasts any bounded busy-loop retry.
		Duration requestTimeout = Duration.ofSeconds(30);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<Object> delivered = new CopyOnWriteArrayList<>();
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(requestTimeout, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> {
					entered.countDown();
					try {
						release.await();
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					delivered.add(params);
					return Mono.empty();
				}), Function.identity());

		Thread inbound = new Thread(() -> transport.simulateIncomingMessage(new AcpSchema.JSONRPCNotification(
				AcpSchema.JSONRPC_VERSION, TEST_NOTIFICATION, Map.of("index", 0))), "inbound");
		inbound.start();
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		var closed = session.closeGracefully().toFuture();
		Thread.sleep(300);
		release.countDown();

		closed.get(5, TimeUnit.SECONDS);
		inbound.join(5_000);
		assertThat(delivered).containsExactly(Map.of("index", 0));
	}

	@Test
	void testConcurrentRequests() {
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(),
				Map.of(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		// Send 5 concurrent requests
		Mono<String> req1 = session.sendRequest("method1", "param1", responseType);
		Mono<String> req2 = session.sendRequest("method2", "param2", responseType);
		Mono<String> req3 = session.sendRequest("method3", "param3", responseType);
		Mono<String> req4 = session.sendRequest("method4", "param4", responseType);
		Mono<String> req5 = session.sendRequest("method5", "param5", responseType);

		// Combine all requests using zip with combinator function
		Mono<String> combined = Mono.zip(arrays -> {
			String result = "";
			for (Object r : arrays) {
				result += r.toString();
			}
			return result;
		}, req1, req2, req3, req4, req5);

		// Simulate responses in different order
		StepVerifier.create(combined).then(() -> {
			var messages = transport.getSentMessages();
			assertThat(messages).hasSize(5);

			// Respond in reverse order to test correlation
			for (int i = messages.size() - 1; i >= 0; i--) {
				var request = (AcpSchema.JSONRPCRequest) messages.get(i);
				transport.simulateIncomingMessage(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION,
						request.id(), "response" + (i + 1), null));
			}
		}).expectNext("response1response2response3response4response5").verifyComplete();

		session.close();
	}

	// ---------------------------------------------------------------------------------
	// Absent values on the wire (found by the NullAway adoption)
	// ---------------------------------------------------------------------------------

	private static AcpSchema.JSONRPCResponse awaitSentResponse(MockAcpClientTransport transport) throws Exception {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			for (AcpSchema.JSONRPCMessage message : transport.getSentMessages()) {
				if (message instanceof AcpSchema.JSONRPCResponse response) {
					return response;
				}
			}
			Thread.sleep(10);
		}
		throw new AssertionError("The session sent no JSON-RPC response");
	}

	@Test
	void requestWithoutParamsReachesItsHandlerAsAnEmptyObject() throws Exception {
		// JSON-RPC lets a request omit params; handlers used to receive null.
		Map<String, AcpClientSession.RequestHandler<?>> requestHandlers = Map.of(ECHO_METHOD,
				params -> Mono.just(String.valueOf(params)));
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, requestHandlers, Map.of(), Function.identity());

		transport.simulateIncomingMessage(
				new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "no-params", ECHO_METHOD, null));

		AcpSchema.JSONRPCResponse response = awaitSentResponse(transport);
		assertThat(response.error()).isNull();
		assertThat(response.result()).isEqualTo("{}");
		session.close();
	}

	@Test
	void requestHandlerCompletingEmptyIsAnsweredWithAnError() throws Exception {
		// An empty handler result used to send no response at all: the peer waited for its
		// timeout. A JSON-RPC request always gets a response.
		Map<String, AcpClientSession.RequestHandler<?>> requestHandlers = Map.of(ECHO_METHOD,
				params -> Mono.empty());
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, requestHandlers, Map.of(), Function.identity());

		transport.simulateIncomingMessage(
				new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "empty", ECHO_METHOD, Map.of()));

		AcpSchema.JSONRPCResponse response = awaitSentResponse(transport);
		assertThat(response.id()).isEqualTo("empty");
		assertThat(response.error()).isNotNull();
		assertThat(response.error().message()).contains("produced no response");
		session.close();
	}

	@Test
	void handlerErrorWithoutMessageStillSendsAnErrorMessage() throws Exception {
		// JSON-RPC requires error.message; an exception without one used to send none.
		Map<String, AcpClientSession.RequestHandler<?>> requestHandlers = Map.of(ECHO_METHOD,
				params -> Mono.error(new IllegalStateException()));
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, requestHandlers, Map.of(), Function.identity());

		transport.simulateIncomingMessage(
				new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "no-message", ECHO_METHOD, Map.of()));

		AcpSchema.JSONRPCResponse response = awaitSentResponse(transport);
		assertThat(response.error()).isNotNull();
		assertThat(response.error().message()).isEqualTo("java.lang.IllegalStateException");
		session.close();
	}

	@Test
	void successResponseWithoutResultFailsTheRequestClearly() {
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		Mono<String> responseMono = session.sendRequest(TEST_METHOD, "test", responseType);

		StepVerifier.create(responseMono).then(() -> {
			AcpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			transport.simulateIncomingMessage(
					new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null, null));
		}).expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("carried no result")
			.isInstanceOfSatisfying(com.agentclientprotocol.sdk.error.AcpProtocolException.class,
					e -> assertThat(e.getCode()).isEqualTo(-32603)))
			.verify(TIMEOUT);

		session.close();
	}


	@Test
	void nullResultForAnEmptyResponseTypeYieldsAnEmptyResponse() {
		// JSON-RPC allows "result": null, and the Python SDK sends it when a handler returns None.
		// Like the Rust SDK, a response type that defaults on null reads it as {}.
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		Mono<AcpSchema.SetSessionModeResponse> responseMono = session.sendRequest(AcpSchema.METHOD_SESSION_SET_MODE,
				new AcpSchema.SetSessionModeRequest("s", "m"), new TypeRef<AcpSchema.SetSessionModeResponse>() {
				});

		StepVerifier.create(responseMono).then(() -> {
			AcpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			transport.simulateIncomingMessage(
					new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null, null));
		}).expectNext(new AcpSchema.SetSessionModeResponse()).verifyComplete();

		session.close();
	}

	@Test
	void nullResultForAMetaOnlyResponseTypeYieldsAResponseWithoutMeta() {
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		Mono<AcpSchema.LogoutResponse> responseMono = session.sendRequest(AcpSchema.METHOD_LOGOUT,
				new AcpSchema.LogoutRequest(), new TypeRef<AcpSchema.LogoutResponse>() {
				});

		StepVerifier.create(responseMono).then(() -> {
			AcpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			transport.simulateIncomingMessage(
					new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null, null));
		}).expectNext(new AcpSchema.LogoutResponse()).verifyComplete();

		session.close();
	}

	@Test
	void nullResultForANonEmptyResponseTypeStillFailsClearly() {
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		Mono<AcpSchema.NewSessionResponse> responseMono = session.sendRequest(AcpSchema.METHOD_SESSION_NEW,
				new AcpSchema.NewSessionRequest("/", List.of()), new TypeRef<AcpSchema.NewSessionResponse>() {
				});

		StepVerifier.create(responseMono).then(() -> {
			AcpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			transport.simulateIncomingMessage(
					new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null, null));
		}).expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("carried no result")).verify(TIMEOUT);

		session.close();
	}

	@Test
	void nullResultForAnExtensionMethodCompletesEmpty() {
		// An extension method's result is free-form, and null is a legal value of it.
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, Map.of(), Map.of(), Function.identity());

		Mono<Map<String, Object>> responseMono = session.sendRequest("_vendor/ping", Map.of(),
				new TypeRef<Map<String, Object>>() {
				});

		StepVerifier.create(responseMono).then(() -> {
			AcpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			transport.simulateIncomingMessage(
					new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null, null));
		}).verifyComplete();

		session.close();
	}

	@Test
	void emptyResponseFromAClientHandlerIsWrittenAsAnEmptyObjectResult() throws Exception {
		// Java answers with "result": {} for an empty response type, never "result": null.
		Map<String, AcpClientSession.RequestHandler<?>> requestHandlers = Map.of(AcpSchema.METHOD_FS_WRITE_TEXT_FILE,
				params -> Mono.just(new AcpSchema.WriteTextFileResponse()));
		var transport = new MockAcpClientTransport();
		var session = new AcpClientSession(TIMEOUT, transport, requestHandlers, Map.of(), Function.identity());

		transport.simulateIncomingMessage(new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "w",
				AcpSchema.METHOD_FS_WRITE_TEXT_FILE, Map.of("sessionId", "s", "path", "/f", "content", "x")));

		AcpSchema.JSONRPCResponse response = awaitSentResponse(transport);
		assertThat(AcpJsonMapper.createDefault().writeValueAsString(response))
			.isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":\"w\",\"result\":{}}");
		session.close();
	}

}
