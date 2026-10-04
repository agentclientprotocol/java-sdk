/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.integration.AcpClientCustomizer;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

/** Customizes the client bean: records the agent's message chunks. */
@Singleton
@Priority(10)
public class RecordingCustomizer implements AcpClientCustomizer {

	final List<String> messages = new CopyOnWriteArrayList<>();

	@Override
	public void customize(AcpClient.AsyncSpec spec) {
		spec.clientInfo(new AcpSchema.Implementation("quarkus-it-client", "1.0.0", null))
			.sessionUpdateHandler(notification -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
						&& chunk.content() instanceof AcpSchema.TextContent text) {
					messages.add(text.text());
				}
				return Mono.empty();
			});
	}

}
