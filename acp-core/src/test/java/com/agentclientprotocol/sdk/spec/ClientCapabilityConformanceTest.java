/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.error.AcpVersionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Client-side obligations of stable ACP v1 that the client SDK itself enforces before a message
 * leaves: nothing the agent did not advertise, and nothing the protocol forbids, is sent.
 *
 * <p>
 * ACP spec 2797d331 (agentclientprotocol/agent-client-protocol), stable v1. Each test cites the
 * derived requirement id of protocol-conformance/spec/requirements.md and the source clause.
 * Found by the whole-roster audit run acp-v1-2797d331-r1-5bb2cf6-001.
 * </p>
 */
class ClientCapabilityConformanceTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String SESSION = "s-1";

	// ACP-V1-INIT-CLIENT-UNSUPPORTED-VERSION, initialization.mdx:98: "If the Client does not support
	// the version specified by the Agent in the initialize response, the Client SHOULD close the
	// connection and inform the user about it."
	@Test
	void anAgentAnsweringAVersionTheClientDoesNotSpeakFailsInitializeAndClosesTheConnection() {
		MockAcpClientTransport transport = agent(Map.of("protocolVersion", 2, "agentCapabilities", Map.of()), Map.of());
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		assertThatThrownBy(() -> client.initialize().block(TIMEOUT)).isInstanceOf(AcpVersionException.class)
			.hasMessageContaining("2")
			.hasMessageContaining(String.valueOf(AcpSchema.LATEST_PROTOCOL_VERSION));
		assertThat(client.getAgentCapabilities()).as("an unusable answer is not kept as negotiated").isNull();
		assertThat(transport.isConnected()).as("the connection is closed").isFalse();
	}

	// ACP-V1-INIT-AGENT-SAME-VERSION, initialization.mdx:96: the same version is accepted as before
	@Test
	void anAgentAnsweringTheSameVersionIsAccepted() {
		MockAcpClientTransport transport = agent(Map.of("protocolVersion", 1, "agentCapabilities", Map.of()), Map.of());
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		assertThat(client.initialize().block(TIMEOUT).protocolVersion()).isEqualTo(1);
		assertThat(client.getAgentCapabilities()).isNotNull();
		client.close();
	}

	// ACP-V1-AUTH-CLIENT-NO-TERMINAL-METHOD-ID, authentication.mdx:151-154: "Clients MUST NOT pass a
	// terminal method." (and :187, "the Client MUST NOT send an authenticate request for a terminal
	// method")
	@Test
	void authenticateRefusesATerminalMethodWithoutSending() {
		MockAcpClientTransport transport = agent(Map.of("protocolVersion", 1, "agentCapabilities", Map.of(),
				"authMethods",
				List.of(Map.of("id", "terminal-login", "name", "Log in from the terminal", "type", "terminal"),
						Map.of("id", "agent-login", "name", "Agent login"))),
				Map.of());
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		transport.clearSentMessages();
		assertThatThrownBy(() -> client.authenticate(new AcpSchema.AuthenticateRequest("terminal-login")).block(TIMEOUT))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("terminal-login")
			.hasMessageContaining("terminal");
		assertThat(transport.getSentMessages()).isEmpty();
		client.authenticate(new AcpSchema.AuthenticateRequest("agent-login")).subscribe(r -> {
		}, e -> {
		});
		assertThat(transport.getSentMessages()).singleElement().isInstanceOf(AcpSchema.JSONRPCRequest.class);
		client.close();
	}

	static Stream<Arguments> sessionCallsWithMcpServers() {
		return Stream.of(
				Arguments.of(Named.of("newSession", (Function<List<AcpSchema.McpServer>, Function<AcpAsyncClient, Mono<?>>>) servers -> c -> c
					.newSession(new AcpSchema.NewSessionRequest("/", servers, null, null)))),
				Arguments.of(Named.of("loadSession", (Function<List<AcpSchema.McpServer>, Function<AcpAsyncClient, Mono<?>>>) servers -> c -> c
					.loadSession(new AcpSchema.LoadSessionRequest(SESSION, "/", servers)))),
				Arguments.of(Named.of("resumeSession", (Function<List<AcpSchema.McpServer>, Function<AcpAsyncClient, Mono<?>>>) servers -> c -> c
					.resumeSession(new AcpSchema.ResumeSessionRequest(SESSION, "/", servers)))));
	}

	// ACP-V1-SETUP-CLIENT-CHECK-MCP-TRANSPORT, session-setup.mdx:522: "Before using HTTP or SSE
	// transports, Clients MUST verify the Agent's capabilities during initialization"
	@ParameterizedTest
	@MethodSource("sessionCallsWithMcpServers")
	void httpAndSseMcpServersAreNotSentToAnAgentThatDidNotAdvertiseThem(
			Function<List<AcpSchema.McpServer>, Function<AcpAsyncClient, Mono<?>>> call) {
		MockAcpClientTransport transport = agent(initialize(Map.of("loadSession", true, "sessionCapabilities",
				Map.of("resume", Map.of()))), Map.of());
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		transport.clearSentMessages();
		List<AcpSchema.McpServer> http = List.of(new AcpSchema.McpServerHttp("api", "https://x/mcp", List.of()));
		List<AcpSchema.McpServer> sse = List.of(new AcpSchema.McpServerSse("events", "https://x/sse", List.of()));
		assertThatThrownBy(() -> call.apply(http).apply(client).block(TIMEOUT)).isInstanceOf(AcpCapabilityException.class)
			.satisfies(e -> assertThat(((AcpCapabilityException) e).getCapability()).isEqualTo("mcpCapabilities.http"));
		assertThatThrownBy(() -> call.apply(sse).apply(client).block(TIMEOUT)).isInstanceOf(AcpCapabilityException.class)
			.satisfies(e -> assertThat(((AcpCapabilityException) e).getCapability()).isEqualTo("mcpCapabilities.sse"));
		assertThat(transport.getSentMessages()).isEmpty();
		// session-setup.mdx:373: stdio needs no capability
		call.apply(List.of(new AcpSchema.McpServerStdio("fs", "/bin/mcp", List.of(), List.of()))).apply(client)
			.subscribe(r -> {
			}, e -> {
			});
		assertThat(transport.getSentMessages()).singleElement().isInstanceOf(AcpSchema.JSONRPCRequest.class);
		client.close();
	}

	@ParameterizedTest
	@MethodSource("sessionCallsWithMcpServers")
	void httpAndSseMcpServersAreSentToAnAgentThatAdvertisedThem(
			Function<List<AcpSchema.McpServer>, Function<AcpAsyncClient, Mono<?>>> call) {
		MockAcpClientTransport transport = agent(initialize(Map.of("loadSession", true, "sessionCapabilities",
				Map.of("resume", Map.of()), "mcpCapabilities", Map.of("http", true, "sse", true))), Map.of());
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		transport.clearSentMessages();
		call.apply(List.of(new AcpSchema.McpServerHttp("api", "https://x/mcp", List.of()),
				new AcpSchema.McpServerSse("events", "https://x/sse", List.of()))).apply(client).subscribe(r -> {
				}, e -> {
				});
		assertThat(transport.getSentMessages()).singleElement().isInstanceOf(AcpSchema.JSONRPCRequest.class);
		client.close();
	}

	static Stream<Arguments> richContent() {
		return Stream.of(
				Arguments.of(Named.of("image", new AcpSchema.ImageContent(null, "aGk=", "image/png", null, null, null)),
						"promptCapabilities.image"),
				Arguments.of(Named.of("audio", new AcpSchema.AudioContent(null, "aGk=", "audio/wav", null, null)),
						"promptCapabilities.audio"),
				Arguments.of(Named.of("embedded resource", new AcpSchema.Resource(null,
						new AcpSchema.TextResourceContents("x", "file:///x", null), null, null)),
						"promptCapabilities.embeddedContext"));
	}

	// ACP-V1-PROMPT-CLIENT-RESTRICT-CONTENT, prompt-turn.mdx:114: "Clients MUST restrict types of
	// content according to the Prompt Capabilities established during initialization."
	@ParameterizedTest
	@MethodSource("richContent")
	void promptContentTheAgentDidNotAdvertiseIsNotSent(AcpSchema.ContentBlock block, String capability) {
		MockAcpClientTransport transport = agent(initialize(Map.of()), Map.of());
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		transport.clearSentMessages();
		AcpSchema.PromptRequest request = new AcpSchema.PromptRequest(SESSION,
				List.of(new AcpSchema.TextContent("look"), block));
		assertThatThrownBy(() -> client.prompt(request).block(TIMEOUT)).isInstanceOf(AcpCapabilityException.class)
			.satisfies(e -> assertThat(((AcpCapabilityException) e).getCapability()).isEqualTo(capability));
		assertThat(transport.getSentMessages()).isEmpty();
		client.close();
	}

	@ParameterizedTest
	@MethodSource("richContent")
	void promptContentTheAgentAdvertisedIsSent(AcpSchema.ContentBlock block, String capability) {
		MockAcpClientTransport transport = agent(initialize(Map.of("promptCapabilities",
				Map.of("image", true, "audio", true, "embeddedContext", true))), Map.of());
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		transport.clearSentMessages();
		client.prompt(new AcpSchema.PromptRequest(SESSION, List.of(block))).subscribe(r -> {
		}, e -> {
		});
		assertThat(transport.getSentMessages()).singleElement().isInstanceOf(AcpSchema.JSONRPCRequest.class);
		client.close();
	}

	// initialization.mdx:204: text and resource links are the baseline every agent supports
	@Test
	void textAndResourceLinksNeedNoPromptCapability() {
		MockAcpClientTransport transport = agent(initialize(Map.of()), Map.of());
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		client.initialize().block(TIMEOUT);
		transport.clearSentMessages();
		client.prompt(new AcpSchema.PromptRequest(SESSION, List.of(new AcpSchema.TextContent("hi"),
				new AcpSchema.ResourceLink(null, "doc", "file:///doc", null, null, null, null, null, null))))
			.subscribe(r -> {
			}, e -> {
			});
		assertThat(transport.getSentMessages()).singleElement().isInstanceOf(AcpSchema.JSONRPCRequest.class);
		client.close();
	}

	private static Map<String, Object> initialize(Map<String, Object> agentCapabilities) {
		return Map.of("protocolVersion", 1, "agentCapabilities", agentCapabilities);
	}

	/** A transport whose agent answers initialize with the given result and session/new. */
	private static MockAcpClientTransport agent(Map<String, Object> initializeResult, Map<String, Object> unused) {
		return new MockAcpClientTransport((t, message) -> {
			if (message instanceof AcpSchema.JSONRPCRequest request) {
				Object result = switch (request.method()) {
					case AcpSchema.METHOD_INITIALIZE -> initializeResult;
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
