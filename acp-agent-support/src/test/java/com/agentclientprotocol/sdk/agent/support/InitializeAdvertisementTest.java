/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.AuthMethod;
import com.agentclientprotocol.sdk.annotation.Authenticate;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.CloseSession;
import com.agentclientprotocol.sdk.annotation.DeleteSession;
import com.agentclientprotocol.sdk.annotation.DisableProvider;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import com.agentclientprotocol.sdk.annotation.ForkSession;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.ListProviders;
import com.agentclientprotocol.sdk.annotation.ListSessions;
import com.agentclientprotocol.sdk.annotation.LoadSession;
import com.agentclientprotocol.sdk.annotation.Logout;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.ResumeSession;
import com.agentclientprotocol.sdk.annotation.SetProvider;
import com.agentclientprotocol.sdk.annotation.SetSessionConfigOption;
import com.agentclientprotocol.sdk.annotation.SetSessionMode;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.AuthCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.AuthMethodAgent;
import com.agentclientprotocol.sdk.spec.AcpSchema.AuthMethodTerminal;
import com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.Implementation;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionCapabilities;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An annotated agent advertises, in its {@code initialize} response, what its annotations
 * declare: the capabilities its handlers imply, its auth methods, the MCP transports and prompt
 * content it accepts, and its name and version as {@code agentInfo}. Each test initializes a
 * real client against the agent.
 */
class InitializeAdvertisementTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@AcpAgent(name = "full-agent", version = "2.1.0", title = "Full Agent", mcpHttp = true, authMethods = {
			@AuthMethod(id = "api-key", name = "API key", description = "Reads FULL_API_KEY"),
			@AuthMethod(id = "login", name = "Log in", type = AuthMethod.Type.TERMINAL, args = "--login",
					env = { "FULL_MODE=login", "FULL_EMPTY=" }) })
	static class FullAgent {

		@Authenticate
		AcpSchema.AuthenticateResponse authenticate(AcpSchema.AuthenticateRequest request) {
			return new AcpSchema.AuthenticateResponse(null);
		}

		@Logout
		AcpSchema.LogoutResponse logout() {
			return new AcpSchema.LogoutResponse(null);
		}

		@NewSession
		AcpSchema.NewSessionResponse newSession() {
			return new AcpSchema.NewSessionResponse("s", null, null);
		}

		@LoadSession
		AcpSchema.LoadSessionResponse load() {
			return null;
		}

		@ListSessions
		AcpSchema.ListSessionsResponse list() {
			return null;
		}

		@ResumeSession
		AcpSchema.ResumeSessionResponse resume() {
			return null;
		}

		@CloseSession
		AcpSchema.CloseSessionResponse close() {
			return null;
		}

		@DeleteSession
		AcpSchema.DeleteSessionResponse delete() {
			return null;
		}

		@SetSessionMode
		AcpSchema.SetSessionModeResponse mode() {
			return null;
		}

		@SetSessionConfigOption
		AcpSchema.SetSessionConfigOptionResponse config() {
			return null;
		}

		@Prompt(image = true, embeddedContext = true)
		PromptResponse prompt() {
			return PromptResponse.endTurn();
		}

		@Cancel
		void cancel() {
		}

		@ExtRequest("_full/ping")
		Map<String, Object> ping() {
			return Map.of();
		}

	}

	@AcpAgent
	static class MinimalAgent {

		@Prompt
		PromptResponse prompt() {
			return PromptResponse.endTurn();
		}

	}

	@AcpAgent(name = "unstable-agent", version = "1")
	static class UnstableAgent {

		@ForkSession
		AcpSchema.ForkSessionResponse fork() {
			return null;
		}

		@ListProviders
		AcpSchema.ListProvidersResponse providers() {
			return null;
		}

		@SetProvider
		AcpSchema.SetProviderResponse setProvider() {
			return null;
		}

		@DisableProvider
		AcpSchema.DisableProviderResponse disableProvider() {
			return null;
		}

	}

	@Test
	void anAgentWithoutInitializeAdvertisesWhatItsAnnotationsDeclare() {
		InitializeResponse response = initialize(AcpAgentSupport.create(new FullAgent()), terminalAuthClient());

		assertThat(response.protocolVersion()).isEqualTo(AcpSchema.LATEST_PROTOCOL_VERSION);
		assertThat(response.agentInfo()).isEqualTo(new Implementation("full-agent", "2.1.0", "Full Agent"));
		AgentCapabilities capabilities = response.agentCapabilities();
		assertThat(capabilities.loadSession()).isTrue();
		SessionCapabilities sessions = capabilities.sessionCapabilities();
		assertThat(sessions).isNotNull();
		assertThat(sessions.list()).isNotNull();
		assertThat(sessions.resume()).isNotNull();
		assertThat(sessions.close()).isNotNull();
		assertThat(sessions.delete()).isNotNull();
		assertThat(sessions.fork()).isNull();
		assertThat(sessions.additionalDirectories()).isNull();
		assertThat(capabilities.auth()).isNotNull();
		assertThat(capabilities.auth().logout()).isNotNull();
		assertThat(capabilities.mcpCapabilities().http()).isTrue();
		assertThat(capabilities.mcpCapabilities().sse()).isFalse();
		assertThat(capabilities.promptCapabilities().image()).isTrue();
		assertThat(capabilities.promptCapabilities().embeddedContext()).isTrue();
		assertThat(capabilities.promptCapabilities().audio()).isFalse();
		assertThat(capabilities.providers()).isNull();
		assertThat(response.authMethods()).containsExactly(
				new AuthMethodAgent("api-key", "API key", "Reads FULL_API_KEY"),
				new AuthMethodTerminal("login", "Log in", null, List.of("--login"),
						Map.of("FULL_MODE", "login", "FULL_EMPTY", ""), null));
	}

	/** What the client concludes from the response: every annotated method is supported. */
	@Test
	void theClientNegotiatesEveryAnnotatedMethod() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport agent = AcpAgentSupport.create(new FullAgent())
			.transport(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.build();
		agent.start();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize().block(TIMEOUT);
			NegotiatedCapabilities negotiated = client.getAgentCapabilities();
			assertThat(negotiated.supportsLoadSession()).isTrue();
			assertThat(negotiated.supportsListSessions()).isTrue();
			assertThat(negotiated.supportsResumeSession()).isTrue();
			assertThat(negotiated.supportsCloseSession()).isTrue();
			assertThat(negotiated.supportsDeleteSession()).isTrue();
			assertThat(negotiated.supportsLogout()).isTrue();
			assertThat(negotiated.supportsImageContent()).isTrue();
			assertThat(negotiated.supportsMcpHttp()).isTrue();
			assertThat(negotiated.supportsForkSession()).isFalse();
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.close();
		}
	}

	@Test
	void terminalAuthMethodsAreAdvertisedOnlyToAClientThatSupportsThem() {
		InitializeResponse response = initialize(AcpAgentSupport.create(new FullAgent()), Function.identity());

		assertThat(response.authMethods()).extracting(AcpSchema.AuthMethod::id).containsExactly("api-key");
	}

	@Test
	void aMinimalAgentAdvertisesNoOptionalCapabilityAndItsClassName() {
		InitializeResponse response = initialize(AcpAgentSupport.create(new MinimalAgent()), Function.identity());

		assertThat(response.agentInfo().name()).isEqualTo("MinimalAgent");
		assertThat(response.agentInfo().version()).isNotBlank();
		AgentCapabilities capabilities = response.agentCapabilities();
		assertThat(capabilities.loadSession()).isFalse();
		assertThat(capabilities.sessionCapabilities()).isNull();
		assertThat(capabilities.auth()).isNull();
		assertThat(capabilities.mcpCapabilities()).isEqualTo(new AcpSchema.McpCapabilities());
		assertThat(capabilities.promptCapabilities()).isEqualTo(new AcpSchema.PromptCapabilities());
		assertThat(response.authMethods()).isNullOrEmpty();
	}

	@Test
	void unstableHandlersAdvertiseTheirUnstableCapabilities() {
		InitializeResponse response = initialize(AcpAgentSupport.create(new UnstableAgent()), Function.identity());

		assertThat(response.agentCapabilities().sessionCapabilities().fork()).isNotNull();
		assertThat(response.agentCapabilities().providers()).isNotNull();
	}

	@AcpAgent(name = "merging-agent", version = "1")
	static class MergingAgent {

		@LoadSession
		AcpSchema.LoadSessionResponse load() {
			return null;
		}

		@Authenticate
		AcpSchema.AuthenticateResponse authenticate() {
			return new AcpSchema.AuthenticateResponse(null);
		}

		@Initialize
		InitializeResponse initialize(InitializeRequest request) {
			// ok() carries loadSession=false and all-false prompt and MCP capabilities
			return new InitializeResponse(1,
					AgentCapabilities.builder()
						.sessionCapabilities(new SessionCapabilities(null, null, null, null, Map.of(), null))
						.promptCapabilities(new AcpSchema.PromptCapabilities(true, false, false))
						.build(),
					List.of(new AuthMethodAgent("token", "Token", "dynamic")), new Implementation("custom", "9"),
					Map.of("k", "v"));
		}

	}

	@AcpAgent(name = "ok-agent", version = "1", authMethods = @AuthMethod(id = "token", name = "Token"))
	static class OkInitializeAgent {

		@LoadSession
		AcpSchema.LoadSessionResponse load() {
			return null;
		}

		@Authenticate
		AcpSchema.AuthenticateResponse authenticate() {
			return new AcpSchema.AuthenticateResponse(null);
		}

		@Initialize
		InitializeResponse initialize() {
			return InitializeResponse.ok();
		}

	}

	/** The pre-0.80 idiom, returning ok(), no longer hides the agent's capabilities. */
	@Test
	void anInitializeReturningOkKeepsTheDerivedResponse() {
		InitializeResponse response = initialize(AcpAgentSupport.create(new OkInitializeAgent()), Function.identity());

		assertThat(response.agentCapabilities().loadSession()).isTrue();
		assertThat(response.authMethods()).extracting(AcpSchema.AuthMethod::id).containsExactly("token");
		assertThat(response.agentInfo()).isEqualTo(new Implementation("ok-agent", "1"));
	}

	@Test
	void anInitializeResponseIsLaidOverTheDerivedOne() {
		InitializeResponse response = initialize(AcpAgentSupport.create(new MergingAgent()), Function.identity());

		AgentCapabilities capabilities = response.agentCapabilities();
		assertThat(capabilities.loadSession()).as("derived, kept although returned false").isTrue();
		assertThat(capabilities.sessionCapabilities().additionalDirectories()).as("returned").isNotNull();
		assertThat(capabilities.promptCapabilities().audio()).as("returned").isTrue();
		assertThat(response.authMethods()).extracting(AcpSchema.AuthMethod::id).containsExactly("token");
		assertThat(response.agentInfo()).isEqualTo(new Implementation("custom", "9"));
		assertThat(response.meta()).containsEntry("k", "v");
	}

	@Test
	void theProtocolVersionIsTheClientsWhenSupportedAndTheLatestOtherwise() {
		assertThat(initialize(AcpAgentSupport.create(new MinimalAgent()), Function.identity(), 1).protocolVersion())
			.isEqualTo(1);
		assertThat(initialize(AcpAgentSupport.create(new MinimalAgent()), Function.identity(), 99).protocolVersion())
			.isEqualTo(AcpSchema.LATEST_PROTOCOL_VERSION);
	}

	@Test
	void aFactoryAdvertisesOnEveryConnection() {
		AcpAgentFactory factory = AcpAgentSupport.create(new FullAgent()).requestTimeout(TIMEOUT).buildFactory();
		StreamableHttpAcpAgentTransport server = new StreamableHttpAcpAgentTransport(0, AcpJsonMapper.createDefault(),
				factory);
		server.start().block(TIMEOUT);
		try {
			for (int i = 0; i < 2; i++) {
				AcpAsyncClient client = AcpClient
					.async(new StreamableHttpAcpClientTransport(
							URI.create("http://127.0.0.1:" + server.getPort()
									+ StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH),
							AcpJsonMapper.createDefault()))
					.requestTimeout(TIMEOUT)
					.build();
				try {
					InitializeResponse response = client.initialize().block(TIMEOUT);
					assertThat(response.agentCapabilities().loadSession()).isTrue();
					assertThat(response.agentCapabilities().sessionCapabilities().list()).isNotNull();
					assertThat(response.agentInfo().name()).isEqualTo("full-agent");
					assertThat(response.authMethods()).extracting(AcpSchema.AuthMethod::id).containsExactly("api-key");
				}
				finally {
					client.closeGracefully().block(TIMEOUT);
				}
			}
		}
		finally {
			server.closeGracefully().block(TIMEOUT);
		}
	}

	@AcpAgent(authMethods = @AuthMethod(id = "api-key", name = "API key"))
	static class AgentMethodWithoutAuthenticate {

	}

	@AcpAgent(authMethods = { @AuthMethod(id = "same", name = "One"), @AuthMethod(id = "same", name = "Two") })
	static class DuplicateAuthMethodIds {

	}

	@AcpAgent(authMethods = @AuthMethod(id = "t", name = "T", type = AuthMethod.Type.TERMINAL, env = "NO_EQUALS"))
	static class MalformedEnv {

	}

	@AcpAgent(authMethods = @AuthMethod(id = "t", name = "T", type = AuthMethod.Type.TERMINAL))
	static class TerminalOnly {

	}

	@Test
	void anAgentAuthMethodNeedsAnAuthenticateHandler() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new AgentMethodWithoutAuthenticate()).buildFactory())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("api-key")
			.hasMessageContaining("@Authenticate");
	}

	@Test
	void aTerminalOnlyAgentNeedsNoAuthenticateHandler() {
		assertThat(AcpAgentSupport.create(new TerminalOnly()).buildFactory()).isNotNull();
	}

	@Test
	void malformedAuthMethodsAreRejected() {
		assertThatThrownBy(() -> AcpAgentSupport.create(new DuplicateAuthMethodIds()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("same");
		assertThatThrownBy(() -> AcpAgentSupport.create(new MalformedEnv()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("NO_EQUALS");
	}

	private static Function<AcpClient.AsyncSpec, AcpClient.AsyncSpec> terminalAuthClient() {
		return spec -> spec.clientCapabilities(
				ClientCapabilities.builder().auth(new AuthCapabilities(true)).build());
	}

	private static InitializeResponse initialize(AcpAgentSupport.Builder builder,
			Function<AcpClient.AsyncSpec, AcpClient.AsyncSpec> clientSpec) {
		return initialize(builder, clientSpec, AcpSchema.LATEST_PROTOCOL_VERSION);
	}

	private static InitializeResponse initialize(AcpAgentSupport.Builder builder,
			Function<AcpClient.AsyncSpec, AcpClient.AsyncSpec> clientSpec, int protocolVersion) {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport agent = builder.transport(pair.agentTransport()).requestTimeout(TIMEOUT).build();
		agent.start();
		AcpAsyncClient client = clientSpec.apply(AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT))
			.build();
		try {
			return client.initialize(protocolVersion, null).block(TIMEOUT);
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.close();
		}
	}

}
