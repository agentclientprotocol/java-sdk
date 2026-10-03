/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.SetSessionMode;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.CurrentModeUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.FileSystemCapability;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every annotated handler, not only {@code @Prompt}, can take its connection's
 * {@link NegotiatedCapabilities} and its connection's agent ({@link AcpSyncAgent} or
 * {@link AcpAsyncAgent}), so it can gate on what the client supports and push session updates
 * outside a prompt turn, including when one handler bean serves many connections through
 * {@code buildFactory()}.
 */
class AcpAgentSupportConnectionContextTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private AcpAgentSupport agentSupport;

	private AcpAsyncClient client;

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.closeGracefully().block(TIMEOUT);
		}
		if (agentSupport != null) {
			agentSupport.close();
		}
	}

	@Test
	void aNonPromptHandlerReceivesTheNegotiatedCapabilities() {
		// Before, only @Prompt was given them: session/new failed with -32603
		// "NegotiatedCapabilities not available in current context".
		AtomicReference<NegotiatedCapabilities> received = new AtomicReference<>();
		AtomicReference<NegotiatedCapabilities> atInitialize = new AtomicReference<>();

		@AcpAgent
		class CapabilitiesAgent {

			@Initialize
			InitializeResponse initialize(NegotiatedCapabilities capabilities) {
				atInitialize.set(capabilities);
				return InitializeResponse.ok();
			}

			@NewSession
			NewSessionResponse newSession(NegotiatedCapabilities capabilities) {
				received.set(capabilities);
				return new NewSessionResponse("s1", null, null);
			}

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
			}

		}

		InMemoryTransportPair pair = InMemoryTransportPair.create();
		agentSupport = AcpAgentSupport.create(new CapabilitiesAgent())
			.transport(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.build();
		agentSupport.start();
		client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(readOnlyFiles(true))
			.build();

		client.initialize().block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

		assertThat(atInitialize.get()).isNotNull();
		assertThat(atInitialize.get().supportsReadTextFile()).isTrue();
		assertThat(received.get()).isNotNull();
		assertThat(received.get().supportsReadTextFile()).isTrue();
		assertThat(received.get().supportsWriteTextFile()).isFalse();
	}

	@Test
	void aNonPromptHandlerReceivesItsAgentAndPushesASessionUpdate() {
		AtomicReference<AcpSyncAgent> syncAgent = new AtomicReference<>();
		List<SessionNotification> updates = new CopyOnWriteArrayList<>();

		@AcpAgent
		class AgentTakingAgent {

			@NewSession
			NewSessionResponse newSession(AcpSyncAgent agent) {
				syncAgent.set(agent);
				return new NewSessionResponse("s1", null, null);
			}

			@SetSessionMode
			SetSessionModeResponse setMode(SetSessionModeRequest request, AcpAsyncAgent agent) {
				agent.sendSessionUpdate(request.sessionId(), new CurrentModeUpdate(request.modeId()))
					.block(TIMEOUT);
				return new SetSessionModeResponse();
			}

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
			}

		}

		InMemoryTransportPair pair = InMemoryTransportPair.create();
		agentSupport = AcpAgentSupport.create(new AgentTakingAgent())
			.transport(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.build();
		agentSupport.start();
		client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).sessionUpdateConsumer(update -> {
			updates.add(update);
			return Mono.empty();
		}).build();

		client.initialize().block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
		client.setSessionMode(new SetSessionModeRequest("s1", "plan")).block(TIMEOUT);

		assertThat(syncAgent.get()).isSameAs(agentSupport.getAgent());
		assertThat(updates).singleElement()
			.satisfies(update -> assertThat(update.update()).isInstanceOf(CurrentModeUpdate.class));
	}

	record Echo(String text) {
	}

	@Test
	void anExtensionHandlerTakesItsParamsWithTheCapabilitiesAndAgent() {
		AtomicReference<AcpAsyncAgent> received = new AtomicReference<>();

		@AcpAgent
		class ExtensionAgent {

			@ExtRequest("_test/echo")
			Echo echo(AcpAsyncAgent agent, Echo params, NegotiatedCapabilities capabilities) {
				received.set(agent);
				return new Echo(params.text() + " " + capabilities.supportsReadTextFile());
			}

			@Prompt
			PromptResponse prompt() {
				return PromptResponse.endTurn();
			}

		}

		InMemoryTransportPair pair = InMemoryTransportPair.create();
		agentSupport = AcpAgentSupport.create(new ExtensionAgent())
			.transport(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.build();
		agentSupport.start();
		client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(readOnlyFiles(true))
			.build();

		client.initialize().block(TIMEOUT);
		Echo echo = client.sendExtRequest("_test/echo", new Echo("read:"), new TypeRef<Echo>() {
		}).block(TIMEOUT);

		assertThat(echo).isEqualTo(new Echo("read: true"));
		assertThat(received.get()).isSameAs(agentSupport.getAgent().async());
	}

	/** One bean behind two connections: each call sees its own connection's capabilities and agent. */
	@AcpAgent
	static class PerConnectionAgent {

		final Map<String, Boolean> readTextFileBySession = new ConcurrentHashMap<>();

		final Map<String, AcpSyncAgent> agentBySession = new ConcurrentHashMap<>();

		@NewSession
		NewSessionResponse newSession(NewSessionRequest request, NegotiatedCapabilities capabilities,
				AcpSyncAgent agent) {
			readTextFileBySession.put(request.cwd(), capabilities.supportsReadTextFile());
			agentBySession.put(request.cwd(), agent);
			return new NewSessionResponse(request.cwd(), null, null);
		}

		@SetSessionMode
		SetSessionModeResponse setMode(SetSessionModeRequest request, AcpSyncAgent agent) {
			agent.sendSessionUpdate(request.sessionId(),
					new AgentMessageChunk(new TextContent("to " + request.sessionId())));
			return new SetSessionModeResponse();
		}

		@Prompt
		PromptResponse prompt() {
			return PromptResponse.endTurn();
		}

	}

	@Test
	void underAFactoryEachConnectionGetsItsOwnCapabilitiesAndAgent() {
		PerConnectionAgent bean = new PerConnectionAgent();
		AcpAgentFactory factory = AcpAgentSupport.create(bean).requestTimeout(TIMEOUT).buildFactory();
		StreamableHttpAcpAgentTransport server = new StreamableHttpAcpAgentTransport(0, AcpJsonMapper.createDefault(),
				factory);
		server.start().block(TIMEOUT);
		List<String> firstUpdates = new CopyOnWriteArrayList<>();
		List<String> secondUpdates = new CopyOnWriteArrayList<>();
		AcpAsyncClient first = client(transport(server), readOnlyFiles(true), firstUpdates);
		AcpAsyncClient second = client(transport(server), readOnlyFiles(false), secondUpdates);
		try {
			first.initialize().block(TIMEOUT);
			second.initialize().block(TIMEOUT);
			first.newSession(new NewSessionRequest("/one", List.of())).block(TIMEOUT);
			second.newSession(new NewSessionRequest("/two", List.of())).block(TIMEOUT);
			first.setSessionMode(new SetSessionModeRequest("/one", "plan")).block(TIMEOUT);
			second.setSessionMode(new SetSessionModeRequest("/two", "plan")).block(TIMEOUT);

			assertThat(bean.readTextFileBySession).containsExactlyInAnyOrderEntriesOf(Map.of("/one", true, "/two", false));
			assertThat(bean.agentBySession.get("/one")).isNotNull().isNotSameAs(bean.agentBySession.get("/two"));
			assertThat(firstUpdates).containsExactly("to /one");
			assertThat(secondUpdates).containsExactly("to /two");
		}
		finally {
			first.closeGracefully().block(TIMEOUT);
			second.closeGracefully().block(TIMEOUT);
			server.closeGracefully().block(TIMEOUT);
		}
	}

	private static ClientCapabilities readOnlyFiles(boolean readTextFile) {
		return new ClientCapabilities(new FileSystemCapability(readTextFile, false), false);
	}

	private static AcpClientTransport transport(StreamableHttpAcpAgentTransport server) {
		return new StreamableHttpAcpClientTransport(
				URI.create("http://127.0.0.1:" + server.getPort() + StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH),
				AcpJsonMapper.createDefault());
	}

	private static AcpAsyncClient client(AcpClientTransport transport, ClientCapabilities capabilities,
			List<String> updates) {
		return AcpClient.async(transport).requestTimeout(TIMEOUT).clientCapabilities(capabilities).sessionUpdateConsumer(notification -> {
			if (notification.update() instanceof AgentMessageChunk chunk && chunk.content() instanceof TextContent text) {
				updates.add(text.text());
			}
			return Mono.empty();
		}).build();
	}

}
