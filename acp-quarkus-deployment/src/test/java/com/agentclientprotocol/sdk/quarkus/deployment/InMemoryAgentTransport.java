/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Replaces the stdio agent transport with an in-memory one (an application bean of type
 * {@link AcpAgentTransport} wins over the extension's default), and builds clients on its
 * other end.
 */
@Singleton
public class InMemoryAgentTransport {

	static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final InMemoryTransportPair pair = InMemoryTransportPair.create();

	final List<String> messages = new CopyOnWriteArrayList<>();

	@Produces
	@Singleton
	AcpAgentTransport agentTransport() {
		return pair.agentTransport();
	}

	/** A client on the other end, collecting the agent's message chunks. */
	AcpSyncClient client() {
		return AcpClient.sync(pair.clientTransport()).requestTimeout(TIMEOUT).sessionUpdateConsumer(notification -> {
			if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
					&& chunk.content() instanceof AcpSchema.TextContent text) {
				messages.add(text.text());
			}
		}).build();
	}

	static AcpSchema.PromptRequest prompt(String sessionId, String text) {
		return new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent(text)));
	}

	static AcpSchema.NewSessionRequest newSession() {
		return new AcpSchema.NewSessionRequest("/tmp", List.of());
	}

}
