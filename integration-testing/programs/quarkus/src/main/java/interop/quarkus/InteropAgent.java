/*
 * Copyright 2025-2026 the original author or authors.
 */

package interop.quarkus;

import java.util.UUID;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.smallrye.mutiny.Uni;

/**
 * The catalogue directives a Quarkus-hosted annotated agent answers (steps.json
 * "directives"): plain text is echoed as the chunks "echo: " and the text; {@code #stop},
 * {@code #len} and {@code #big} as the catalogue says; any other directive is -32602. The
 * prompt answers through a Mutiny {@code Uni}.
 */
@AcpAgent(name = "interop-quarkus-agent", version = "1")
public class InteropAgent {

	@Initialize
	AcpSchema.InitializeResponse initialize(AcpSchema.InitializeRequest request) {
		return new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), java.util.List.of(),
				new AcpSchema.Implementation("interop-quarkus-agent", "1", null), null);
	}

	@NewSession
	AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest request) {
		return new AcpSchema.NewSessionResponse(UUID.randomUUID().toString(), null, null);
	}

	@Prompt
	Uni<AcpSchema.PromptResponse> prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
		return Uni.createFrom().item(() -> answer(text(request), context));
	}

	private static AcpSchema.PromptResponse answer(String text, SyncPromptContext context) {
		if (!text.startsWith("#")) {
			context.sendMessage("echo: ");
			context.sendMessage(text);
			return AcpSchema.PromptResponse.endTurn();
		}
		int space = text.indexOf(' ');
		String name = space < 0 ? text : text.substring(0, space);
		String args = space < 0 ? "" : text.substring(space + 1);
		switch (name) {
			case "#stop" -> {
				context.sendMessage("stop");
				return new AcpSchema.PromptResponse(AcpSchema.StopReason.of(args));
			}
			case "#len" -> {
				context.sendMessage("len=" + args.length());
				return AcpSchema.PromptResponse.endTurn();
			}
			case "#big" -> {
				context.sendMessage("x".repeat(Integer.parseInt(args.strip())));
				return AcpSchema.PromptResponse.endTurn();
			}
			default -> throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "unknown directive: " + name);
		}
	}

	private static String text(AcpSchema.PromptRequest request) {
		for (AcpSchema.ContentBlock block : request.prompt()) {
			if (block instanceof AcpSchema.TextContent content) {
				return content.text();
			}
		}
		return "";
	}

}
