/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client SDK refuses a session call whose working directory or additional directories are not
 * absolute paths, without sending it.
 *
 * <p>
 * ACP spec 2797d331 (agentclientprotocol/agent-client-protocol), stable v1. Each test cites the
 * derived requirement id of protocol-conformance/spec/requirements.md and the source clause.
 * Found by the whole-roster audit run acp-v1-2797d331-r1-44f2a09-002. "Absolute" is checked by
 * form, POSIX or Windows, not by {@link java.nio.file.Path#isAbsolute()}: the agent may run on
 * another host and operating system than the client.
 * </p>
 */
class SessionPathConformanceTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String SESSION = "s-1";

	private static final String ROOT = "/workspace";

	/** A session call from its cwd and additional directories. */
	interface SessionCall extends BiFunction<String, List<String>, java.util.function.Function<AcpAsyncClient, Mono<?>>> {

	}

	static Stream<Arguments> sessionCalls() {
		return Stream.of(
				Arguments.of(Named.of("newSession", (SessionCall) (cwd, dirs) -> c -> c
					.newSession(new AcpSchema.NewSessionRequest(cwd, List.of(), dirs, null)))),
				Arguments.of(Named.of("loadSession", (SessionCall) (cwd, dirs) -> c -> c
					.loadSession(new AcpSchema.LoadSessionRequest(SESSION, cwd, List.of(), dirs, null)))),
				Arguments.of(Named.of("resumeSession", (SessionCall) (cwd, dirs) -> c -> c
					.resumeSession(new AcpSchema.ResumeSessionRequest(SESSION, cwd, List.of(), dirs, null)))));
	}

	// ACP-V1-SETUP-CWD-ABSOLUTE, session-setup.mdx:362: the cwd "MUST be an absolute path"
	@ParameterizedTest
	@MethodSource("sessionCalls")
	void aRelativeWorkingDirectoryIsNotSent(SessionCall call) {
		for (String cwd : List.of(".", "", "workspace", "./src", "~/project", "C:relative", "file:///workspace")) {
			MockAcpClientTransport transport = initializedAgent();
			AcpAsyncClient client = client(transport);
			assertThatThrownBy(() -> call.apply(cwd, null).apply(client).block(TIMEOUT)).as(cwd)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cwd")
				.hasMessageContaining("absolute");
			assertThat(transport.getSentMessages()).as(cwd).isEmpty();
			client.close();
		}
	}

	// ACP-V1-SETUP-ADDITIONAL-DIRS-ABSOLUTE, session-setup.mdx:340: "each additionalDirectories entry
	// MUST be an absolute path"
	@ParameterizedTest
	@MethodSource("sessionCalls")
	void aRelativeAdditionalDirectoryIsNotSent(SessionCall call) {
		MockAcpClientTransport transport = initializedAgent();
		AcpAsyncClient client = client(transport);
		assertThatThrownBy(() -> call.apply(ROOT, List.of("/docs", "../shared")).apply(client).block(TIMEOUT))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("additionalDirectories")
			.hasMessageContaining("../shared");
		assertThat(transport.getSentMessages()).isEmpty();
		client.close();
	}

	// POSIX, Windows drive and UNC forms are all absolute, whatever the client's own OS
	@ParameterizedTest
	@ValueSource(strings = { "/", "/workspace", "//server/share", "C:\\Users\\dev\\project", "d:/work",
			"\\\\server\\share\\repo", "\\\\?\\C:\\long\\path" })
	void absolutePathsOfEitherFamilyAreSent(String path) {
		MockAcpClientTransport transport = initializedAgent();
		AcpAsyncClient client = client(transport);
		client.newSession(new AcpSchema.NewSessionRequest(path, List.of(), List.of(path), null)).subscribe(r -> {
		}, e -> {
		});
		assertThat(transport.getSentMessages()).singleElement().isInstanceOf(AcpSchema.JSONRPCRequest.class);
		client.close();
	}

	private static AcpAsyncClient client(MockAcpClientTransport transport) {
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		transport.clearSentMessages();
		return client;
	}

	/** An agent that supports load, resume and additional directories, and answers session/new. */
	private static MockAcpClientTransport initializedAgent() {
		Map<String, Object> initialize = Map.of("protocolVersion", 1, "agentCapabilities",
				Map.of("loadSession", true, "sessionCapabilities",
						Map.of("resume", Map.of(), "additionalDirectories", Map.of())));
		return new MockAcpClientTransport((t, message) -> {
			if (message instanceof AcpSchema.JSONRPCRequest request) {
				Object result = switch (request.method()) {
					case AcpSchema.METHOD_INITIALIZE -> initialize;
					case AcpSchema.METHOD_SESSION_NEW -> Map.of("sessionId", SESSION);
					default -> null;
				};
				if (result != null) {
					t.simulateIncomingMessage(
							new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), result, null));
				}
			}
		});
	}

}
