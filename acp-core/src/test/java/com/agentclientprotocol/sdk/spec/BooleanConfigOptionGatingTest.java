/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent SDK sends a {@code boolean} session config option only to a client that advertised
 * {@code session.configOptions.boolean}; to any other client the option is omitted from session
 * answers, {@code session/set_config_option} answers and {@code config_option_update}
 * notifications, and a warning names it.
 *
 * <p>
 * ACP spec 2797d331, session-config-options.mdx:187: "Agents MUST NOT include type: "boolean"
 * options in configOptions payloads unless the Client advertised support." Derived requirement
 * ACP-V1-CONFIG-NO-BOOLEAN-WITHOUT-CAPABILITY, found by audit run acp-v1-2797d331-r1-5bb2cf6-001.
 * </p>
 */
class BooleanConfigOptionGatingTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final AcpSchema.SessionConfigBoolean BRAVE = new AcpSchema.SessionConfigBoolean("brave", "Brave mode",
			true);

	private static final AcpSchema.SessionConfigSelect MODE = new AcpSchema.SessionConfigSelect("mode", "Mode", "ask",
			List.of(new AcpSchema.SessionConfigSelectOption("ask", "Ask"),
					new AcpSchema.SessionConfigSelectOption("code", "Code")));

	private final InMemoryTransportPair pair = InMemoryTransportPair.create();

	private final List<AcpSchema.SessionNotification> updates = new CopyOnWriteArrayList<>();

	private AcpAsyncAgent agent;

	private AcpAsyncClient client;

	@AfterEach
	void stop() {
		if (client != null) {
			client.close();
		}
		if (agent != null) {
			agent.close();
		}
		pair.closeGracefully().block(TIMEOUT);
	}

	private void startAgent() {
		agent = AcpAgent.async(pair.agentTransport())
			.newSessionHandler(request -> Mono
				.just(new AcpSchema.NewSessionResponse("s-1", null, List.of(MODE, BRAVE), null)))
			.setSessionConfigOptionHandler(request -> Mono
				.just(new AcpSchema.SetSessionConfigOptionResponse(List.of(MODE, BRAVE))))
			.promptHandler((request, context) -> context
				.sendSessionUpdate(new AcpSchema.ConfigOptionUpdate(null, List.of(MODE, BRAVE), null))
				.thenReturn(AcpSchema.PromptResponse.endTurn()))
			.build();
		agent.start().block(TIMEOUT);
	}

	private AcpAsyncClient client(AcpSchema.ClientCapabilities capabilities) {
		client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(capabilities)
			.sessionUpdateHandler(notification -> Mono.fromRunnable(() -> updates.add(notification)))
			.build();
		client.initialize().block(TIMEOUT);
		return client;
	}

	// ACP spec 2797d331: session-config-options.mdx:187, "MUST NOT include type: "boolean" options ...
	// unless the Client advertised support"
	@Test
	void aBooleanOptionIsOmittedForAClientThatDidNotAdvertiseIt() {
		startAgent();
		AcpAsyncClient client = client(new AcpSchema.ClientCapabilities());

		AcpSchema.NewSessionResponse session = client.newSession(new AcpSchema.NewSessionRequest("/w", List.of()))
			.block(TIMEOUT);
		assertThat(session.configOptions()).containsExactly(MODE);

		AcpSchema.SetSessionConfigOptionResponse set = client
			.setSessionConfigOption(AcpSchema.SetSessionConfigOptionRequest.select("s-1", "mode", "code"))
			.block(TIMEOUT);
		assertThat(set.configOptions()).containsExactly(MODE);

		client.prompt(AcpSchema.PromptRequest.text("s-1", "go")).block(TIMEOUT);
		assertThat(updates).singleElement()
			.extracting(AcpSchema.SessionNotification::update)
			.isEqualTo(new AcpSchema.ConfigOptionUpdate(null, List.of(MODE), null));
	}

	// session-config-options.mdx:156: with the capability advertised the option is sent
	@Test
	void aBooleanOptionIsSentToAClientThatAdvertisedIt() {
		startAgent();
		AcpAsyncClient client = client(new AcpSchema.ClientCapabilities(null, null,
				AcpSchema.ClientSessionCapabilities.withBooleanConfigOptions(), null, null, null));

		AcpSchema.NewSessionResponse session = client.newSession(new AcpSchema.NewSessionRequest("/w", List.of()))
			.block(TIMEOUT);
		assertThat(session.configOptions()).containsExactly(MODE, BRAVE);

		client.prompt(AcpSchema.PromptRequest.text("s-1", "go")).block(TIMEOUT);
		assertThat(updates).singleElement()
			.extracting(AcpSchema.SessionNotification::update)
			.isEqualTo(new AcpSchema.ConfigOptionUpdate(null, List.of(MODE, BRAVE), null));
	}

}
