/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A client follows ACP's order: every ACP call but {@code initialize} fails with
 * {@link IllegalStateException} until the agent has answered {@code initialize}, and a call the
 * agent did not advertise fails with {@link AcpCapabilityException}, as the agent side does for
 * the client's capabilities. Neither sends anything. Extension methods are outside the lifecycle
 * and not checked ({@code ExtensionMethodsTest.extHandlersDoNotNeedInitialization}).
 */
class ClientCallGatingTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String SESSION = "s-1";

	static Stream<Arguments> everyCall() {
		return Stream.of(call("authenticate", c -> c.authenticate(new AcpSchema.AuthenticateRequest("m"))),
				call("logout", c -> c.logout(new AcpSchema.LogoutRequest())),
				call("newSession", c -> c.newSession(new AcpSchema.NewSessionRequest("/"))),
				call("loadSession", c -> c.loadSession(new AcpSchema.LoadSessionRequest(SESSION, "/", List.of()))),
				call("setSessionMode", c -> c.setSessionMode(new AcpSchema.SetSessionModeRequest(SESSION, "m"))),
				call("listSessions", c -> c.listSessions(new AcpSchema.ListSessionsRequest(null))),
				call("closeSession", c -> c.closeSession(new AcpSchema.CloseSessionRequest(SESSION))),
				call("deleteSession", c -> c.deleteSession(new AcpSchema.DeleteSessionRequest(SESSION))),
				call("resumeSession", c -> c.resumeSession(new AcpSchema.ResumeSessionRequest(SESSION, "/", List.of()))),
				call("forkSession", c -> c.forkSession(new AcpSchema.ForkSessionRequest(SESSION, "/", List.of()))),
				call("setSessionConfigOption",
						c -> c.setSessionConfigOption(AcpSchema.SetSessionConfigOptionRequest.select(SESSION, "o", "v"))),
				call("listProviders", c -> c.listProviders(new AcpSchema.ListProvidersRequest())),
				call("setProvider", c -> c.setProvider(new AcpSchema.SetProviderRequest("p", "openai", "http://x"))),
				call("disableProvider", c -> c.disableProvider(new AcpSchema.DisableProviderRequest("p"))),
				call("prompt", c -> c.prompt(AcpSchema.PromptRequest.text(SESSION, "hi"))),
				call("cancel", c -> c.cancel(new AcpSchema.CancelNotification(SESSION))));
	}

	static Stream<Arguments> advertisedCalls() {
		return Stream.of(
				advertised("loadSession", "loadSession",
						c -> c.loadSession(new AcpSchema.LoadSessionRequest(SESSION, "/", List.of()))),
				advertised("listSessions", "sessionCapabilities.list",
						c -> c.listSessions(new AcpSchema.ListSessionsRequest(null))),
				advertised("closeSession", "sessionCapabilities.close",
						c -> c.closeSession(new AcpSchema.CloseSessionRequest(SESSION))),
				advertised("deleteSession", "sessionCapabilities.delete",
						c -> c.deleteSession(new AcpSchema.DeleteSessionRequest(SESSION))),
				advertised("resumeSession", "sessionCapabilities.resume",
						c -> c.resumeSession(new AcpSchema.ResumeSessionRequest(SESSION, "/", List.of()))),
				advertised("forkSession", "sessionCapabilities.fork",
						c -> c.forkSession(new AcpSchema.ForkSessionRequest(SESSION, "/", List.of()))),
				advertised("logout", "auth.logout", c -> c.logout(new AcpSchema.LogoutRequest())),
				advertised("listProviders", "providers", c -> c.listProviders(new AcpSchema.ListProvidersRequest())),
				advertised("setProvider", "providers",
						c -> c.setProvider(new AcpSchema.SetProviderRequest("p", "openai", "http://x"))),
				advertised("disableProvider", "providers",
						c -> c.disableProvider(new AcpSchema.DisableProviderRequest("p"))));
	}

	@ParameterizedTest
	@MethodSource("everyCall")
	void aCallBeforeInitializeFailsWithoutSending(Function<AcpAsyncClient, Mono<?>> call) {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		assertThatThrownBy(() -> call.apply(client).block(TIMEOUT)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("initialize()");
		assertThat(transport.getSentMessages()).isEmpty();
		client.close();
	}

	@ParameterizedTest
	@MethodSource("advertisedCalls")
	void aCallTheAgentDidNotAdvertiseFailsWithoutSending(Function<AcpAsyncClient, Mono<?>> call, String capability) {
		MockAcpClientTransport transport = agentAdvertising(Map.of());
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		transport.clearSentMessages();

		assertThatThrownBy(() -> call.apply(client).block(TIMEOUT)).isInstanceOf(AcpCapabilityException.class)
			.satisfies(e -> assertThat(((AcpCapabilityException) e).getCapability()).isEqualTo(capability));
		assertThat(transport.getSentMessages()).isEmpty();
		client.close();
	}

	@ParameterizedTest
	@MethodSource("advertisedCalls")
	void aCallTheAgentAdvertisedIsSent(Function<AcpAsyncClient, Mono<?>> call, String capability) {
		MockAcpClientTransport transport = agentAdvertising(Map.of("loadSession", true, "sessionCapabilities",
				Map.of("list", Map.of(), "close", Map.of(), "delete", Map.of(), "resume", Map.of(), "fork", Map.of()),
				"auth", Map.of("logout", Map.of()), "providers", Map.of()));
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		transport.clearSentMessages();

		call.apply(client).subscribe(result -> {
		}, error -> {
		});

		assertThat(transport.getSentMessages()).singleElement().isInstanceOf(AcpSchema.JSONRPCRequest.class);
		client.close();
	}

	@Test
	void aCallBuiltBeforeInitializeIsCheckedWhenSubscribed() {
		MockAcpClientTransport transport = agentAdvertising(Map.of());
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();

		AcpSchema.NewSessionResponse response = client.initialize()
			.then(client.newSession(new AcpSchema.NewSessionRequest("/")))
			.block(TIMEOUT);

		assertThat(response.sessionId()).isEqualTo(SESSION);
		client.close();
	}

	@Test
	void theSyncClientThrowsTheSame() {
		MockAcpClientTransport transport = agentAdvertising(Map.of());
		AcpSyncClient client = AcpClient.sync(transport).requestTimeout(TIMEOUT).build();

		assertThatThrownBy(() -> client.newSession(new AcpSchema.NewSessionRequest("/")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("initialize()");
		client.initialize();
		assertThatThrownBy(() -> client.loadSession(new AcpSchema.LoadSessionRequest(SESSION, "/", List.of())))
			.isInstanceOf(AcpCapabilityException.class);
		assertThat(client.newSession(new AcpSchema.NewSessionRequest("/")).sessionId()).isEqualTo(SESSION);
		client.close();
	}

	/** A transport whose agent answers initialize with the given capabilities and session/new. */
	private static MockAcpClientTransport agentAdvertising(Map<String, Object> agentCapabilities) {
		return new MockAcpClientTransport((t, message) -> {
			if (message instanceof AcpSchema.JSONRPCRequest request) {
				Object result = switch (request.method()) {
					case AcpSchema.METHOD_INITIALIZE -> Map.of("protocolVersion", 1, "agentCapabilities",
							agentCapabilities);
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

	private static Arguments call(String name, Function<AcpAsyncClient, Mono<?>> call) {
		return Arguments.of(Named.of(name, call));
	}

	private static Arguments advertised(String name, String capability, Function<AcpAsyncClient, Mono<?>> call) {
		return Arguments.of(Named.of(name, call), capability);
	}

}
