/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.integration.AcpSessionUpdateHandler;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import jakarta.inject.Singleton;

/** The chat: collects the text the agent streams. */
@Singleton
public class Chat implements AcpSessionUpdateHandler {

	private final List<String> messages = new CopyOnWriteArrayList<>();

	@Override
	public void onSessionUpdate(AcpSchema.SessionNotification notification) {
		if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
				&& chunk.content() instanceof AcpSchema.TextContent text) {
			messages.add(text.text());
		}
	}

	/**
	 * The text received so far.
	 * @return the messages, oldest first
	 */
	public List<String> messages() {
		return List.copyOf(messages);
	}

}
