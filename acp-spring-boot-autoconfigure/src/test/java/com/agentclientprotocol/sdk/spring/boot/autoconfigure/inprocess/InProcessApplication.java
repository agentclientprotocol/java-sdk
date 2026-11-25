/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.inprocess;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.integration.AcpPermissionHandler;
import com.agentclientprotocol.sdk.integration.AcpSessionUpdateHandler;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * An application that runs its {@link ClaimsAgent} in process with configuration only: no
 * transport, pair, client or lifecycle code. It has two handler beans of its own.
 */
@SpringBootApplication
public class InProcessApplication {

	/** The chat: collects the text the agent streams. */
	@Component
	static class Chat implements AcpSessionUpdateHandler {

		final List<String> messages = new CopyOnWriteArrayList<>();

		@Override
		public void onSessionUpdate(AcpSchema.SessionNotification notification) {
			if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
					&& chunk.content() instanceof AcpSchema.TextContent text) {
				messages.add(text.text());
			}
		}

	}

	/** A reviewer who approves every claim; absent with {@code test.reviewer=false}. */
	@Component
	@ConditionalOnProperty(name = "test.reviewer", havingValue = "true", matchIfMissing = true)
	static class Reviewer implements AcpPermissionHandler {

		final List<String> asked = new CopyOnWriteArrayList<>();

		@Override
		public AcpSchema.RequestPermissionResponse onPermissionRequest(AcpSchema.RequestPermissionRequest request) {
			asked.add(String.valueOf(request.toolCall().title()));
			AcpSchema.PermissionOption allow = request.options()
				.stream()
				.filter(option -> option.kind().equals(AcpSchema.PermissionOptionKind.ALLOW_ONCE))
				.findFirst()
				.orElseThrow();
			return new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected(allow.optionId()));
		}

	}

}
