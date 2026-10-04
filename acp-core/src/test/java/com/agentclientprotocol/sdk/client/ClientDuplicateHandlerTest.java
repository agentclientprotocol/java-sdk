/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registering a second handler for a method the client builders already have one for is a
 * mistake that used to replace the first silently. It now fails at the second call, naming the
 * setter; a null handler fails at once.
 */
class ClientDuplicateHandlerTest {

	static Stream<Arguments> asyncRegistrations() {
		return Stream.of(
				async("readTextFileHandler", spec -> spec.readTextFileHandler(r -> Mono.empty())),
				async("writeTextFileHandler", spec -> spec.writeTextFileHandler(r -> Mono.empty())),
				async("requestPermissionHandler", spec -> spec.requestPermissionHandler(r -> Mono.empty())),
				async("createTerminalHandler", spec -> spec.createTerminalHandler(r -> Mono.empty())),
				async("terminalOutputHandler", spec -> spec.terminalOutputHandler(r -> Mono.empty())),
				async("releaseTerminalHandler", spec -> spec.releaseTerminalHandler(r -> Mono.empty())),
				async("waitForTerminalExitHandler", spec -> spec.waitForTerminalExitHandler(r -> Mono.empty())),
				async("killTerminalHandler", spec -> spec.killTerminalHandler(r -> Mono.empty())),
				async("createElicitationHandler", spec -> spec.createElicitationHandler(r -> Mono.empty())),
				async("completeElicitationHandler", spec -> spec.completeElicitationHandler(n -> Mono.empty())),
				async("requestHandler", spec -> spec.requestHandler("_x/raw", params -> Mono.just("raw"))),
				async("notificationHandler", spec -> spec.notificationHandler("_x/note", params -> Mono.empty())),
				async("extRequestHandler", spec -> spec.extRequestHandler("_x/ext", params -> Mono.just("ok"))),
				async("extNotificationHandler", spec -> spec.extNotificationHandler("_x/extnote", params -> Mono.empty())));
	}

	static Stream<Arguments> syncRegistrations() {
		return Stream.of(
				sync("readTextFileHandler", spec -> spec.readTextFileHandler(r -> null)),
				sync("writeTextFileHandler", spec -> spec.writeTextFileHandler(r -> null)),
				sync("requestPermissionHandler", spec -> spec.requestPermissionHandler(r -> null)),
				sync("createTerminalHandler", spec -> spec.createTerminalHandler(r -> null)),
				sync("terminalOutputHandler", spec -> spec.terminalOutputHandler(r -> null)),
				sync("releaseTerminalHandler", spec -> spec.releaseTerminalHandler(r -> null)),
				sync("waitForTerminalExitHandler", spec -> spec.waitForTerminalExitHandler(r -> null)),
				sync("killTerminalHandler", spec -> spec.killTerminalHandler(r -> null)),
				sync("createElicitationHandler", spec -> spec.createElicitationHandler(r -> null)),
				sync("completeElicitationHandler", spec -> spec.completeElicitationHandler(n -> {
				})),
				sync("requestHandler", spec -> spec.requestHandler("_x/raw", params -> "raw")),
				sync("extRequestHandler", spec -> spec.extRequestHandler("_x/ext", params -> "ok")),
				sync("extNotificationHandler", spec -> spec.extNotificationHandler("_x/extnote", params -> {
				})));
	}

	@ParameterizedTest
	@MethodSource("asyncRegistrations")
	void aSecondAsyncRegistrationIsRefused(Consumer<AcpClient.AsyncSpec> register, String setter) {
		AcpClient.AsyncSpec spec = AcpClient.async(new MockAcpClientTransport());
		register.accept(spec);

		assertThatThrownBy(() -> register.accept(spec)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("already registered")
			.hasMessageContaining(setter);
	}

	@ParameterizedTest
	@MethodSource("syncRegistrations")
	void aSecondSyncRegistrationIsRefused(Consumer<AcpClient.SyncSpec> register, String setter) {
		AcpClient.SyncSpec spec = AcpClient.sync(new MockAcpClientTransport());
		register.accept(spec);

		assertThatThrownBy(() -> register.accept(spec)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("already registered")
			.hasMessageContaining(setter);
	}

	@Test
	void anExtensionHandlerAndARawHandlerForTheSameMethodAreRefused() {
		AcpClient.AsyncSpec spec = AcpClient.async(new MockAcpClientTransport())
			.extRequestHandler("_x/both", params -> Mono.just("ext"));

		assertThatThrownBy(() -> spec.requestHandler("_x/both", params -> Mono.just("raw")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("_x/both")
			.hasMessageContaining("requestHandler");
	}

	@Test
	void nullHandlersFailAtOnce() {
		AcpClient.AsyncSpec async = AcpClient.async(new MockAcpClientTransport());
		AcpClient.SyncSpec sync = AcpClient.sync(new MockAcpClientTransport());
		List<Runnable> nullRegistrations = List.of(() -> async.readTextFileHandler(null),
				() -> async.createElicitationHandler(null), () -> async.completeElicitationHandler(null),
				() -> async.sessionUpdateHandler(null), () -> async.requestHandler("_x/a", null),
				() -> async.notificationHandler("_x/b", null), () -> async.extRequestHandler("_x/c", null),
				() -> async.extNotificationHandler("_x/d", null), () -> sync.readTextFileHandler(null),
				() -> sync.killTerminalHandler(null), () -> sync.sessionUpdateHandler(null),
				() -> sync.requestHandler("_x/e", null), () -> sync.extRequestHandler("_x/f", null),
				() -> sync.extNotificationHandler("_x/g", null));
		for (Runnable registration : nullRegistrations) {
			assertThatThrownBy(registration::run).isInstanceOf(IllegalArgumentException.class);
		}
	}

	private static Arguments async(String setter, Consumer<AcpClient.AsyncSpec> registration) {
		return Arguments.of(Named.of(setter, registration), setter);
	}

	private static Arguments sync(String setter, Consumer<AcpClient.SyncSpec> registration) {
		return Arguments.of(Named.of(setter, registration), setter);
	}

}
