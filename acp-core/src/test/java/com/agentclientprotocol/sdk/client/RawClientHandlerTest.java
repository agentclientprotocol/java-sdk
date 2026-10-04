/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The raw {@code requestHandler} and {@code notificationHandler} are the escape hatch for methods
 * the SDK does not model: for a method with a typed setter they would bypass its typed params and
 * its checks (the elicitation mode check), so they refuse it and name the setter to use. The sync
 * builder's raw notification handler is a blocking consumer, as its other handlers are.
 */
class RawClientHandlerTest {

	static Stream<Arguments> typedRequests() {
		return Stream.of(Arguments.of(AcpSchema.METHOD_FS_READ_TEXT_FILE, "readTextFileHandler"),
				Arguments.of(AcpSchema.METHOD_FS_WRITE_TEXT_FILE, "writeTextFileHandler"),
				Arguments.of(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, "requestPermissionHandler"),
				Arguments.of(AcpSchema.METHOD_TERMINAL_CREATE, "createTerminalHandler"),
				Arguments.of(AcpSchema.METHOD_TERMINAL_OUTPUT, "terminalOutputHandler"),
				Arguments.of(AcpSchema.METHOD_TERMINAL_RELEASE, "releaseTerminalHandler"),
				Arguments.of(AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT, "waitForTerminalExitHandler"),
				Arguments.of(AcpSchema.METHOD_TERMINAL_KILL, "killTerminalHandler"),
				Arguments.of(AcpSchema.METHOD_ELICITATION_CREATE, "createElicitationHandler"));
	}

	static Stream<Arguments> typedNotifications() {
		return Stream.of(Arguments.of(AcpSchema.METHOD_SESSION_UPDATE, "sessionUpdateConsumer"),
				Arguments.of(AcpSchema.METHOD_ELICITATION_COMPLETE, "completeElicitationHandler"));
	}

	@ParameterizedTest
	@MethodSource("typedRequests")
	void aRawRequestHandlerForATypedMethodIsRefused(String method, String setter) {
		assertThatThrownBy(() -> AcpClient.async(new MockAcpClientTransport())
			.requestHandler(method, params -> Mono.just("raw"))).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(method)
			.hasMessageContaining(setter);
		assertThatThrownBy(() -> AcpClient.sync(new MockAcpClientTransport()).requestHandler(method, params -> "raw"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(setter);
	}

	@ParameterizedTest
	@MethodSource("typedNotifications")
	void aRawNotificationHandlerForATypedMethodIsRefused(String method, String setter) {
		assertThatThrownBy(() -> AcpClient.async(new MockAcpClientTransport())
			.notificationHandler(method, params -> Mono.empty())).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(method)
			.hasMessageContaining(setter);
		assertThatThrownBy(() -> AcpClient.sync(new MockAcpClientTransport()).notificationHandler(method, params -> {
		})).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(setter);
	}

	@Test
	void aRawRequestHandlerStillServesAMethodTheSdkDoesNotModel() throws Exception {
		CompletableFuture<AcpSchema.JSONRPCResponse> answer = new CompletableFuture<>();
		MockAcpClientTransport transport = new MockAcpClientTransport((t, message) -> {
			if (message instanceof AcpSchema.JSONRPCResponse response) {
				answer.complete(response);
			}
		});
		AcpAsyncClient client = AcpClient.async(transport)
			.requestHandler("future/method", params -> Mono.just(Map.of("ok", true)))
			.build();

		transport.simulateIncomingMessage(
				new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "r-1", "future/method", Map.of()));

		assertThat(answer.get(5, TimeUnit.SECONDS).result()).isEqualTo(Map.of("ok", true));
		client.close();
	}

	@Test
	void theSyncRawNotificationHandlerIsABlockingConsumer() throws Exception {
		CompletableFuture<Object> received = new CompletableFuture<>();
		CompletableFuture<String> thread = new CompletableFuture<>();
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpSyncClient client = AcpClient.sync(transport).notificationHandler("future/note", params -> {
			thread.complete(Thread.currentThread().getName());
			received.complete(params);
		}).build();

		transport.simulateIncomingMessage(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION, "future/note",
				Map.of("n", 1)));

		assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo(Map.of("n", 1));
		assertThat(thread.get(5, TimeUnit.SECONDS)).startsWith("acp-sync-handler");
		client.close();
	}

}
