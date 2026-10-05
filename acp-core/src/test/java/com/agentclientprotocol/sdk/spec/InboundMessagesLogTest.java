/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Map;
import java.util.function.BiFunction;

import com.agentclientprotocol.sdk.CapturedLogs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How {@link InboundMessages} logs a notification whose handler failed.
 */
class InboundMessagesLogTest {

	/**
	 * A notification handler that called the peer and let its {@link AcpError} escape: the
	 * error's message and data are the peer's text, so at INFO and above the failure is
	 * logged by its code and the SDK's description of the code only.
	 */
	@Test
	void anEscapedAcpErrorIsLoggedWithoutThePeersMessageOrDataAboveDebug() {
		AcpError peerError = new AcpError(
				new AcpSchema.JSONRPCError(-32603, "MESSAGE-SECRET", Map.of("details", "DATA-SECRET")));
		BiFunction<String, Object, Mono<Void>> handle = (handler, params) -> Mono.error(peerError);

		try (CapturedLogs logs = CapturedLogs.open()) {
			InboundMessages
				.deliver(LoggerFactory.getLogger(AcpClientSession.class),
						new AcpSchema.JSONRPCNotification("session/update", Map.of()), Map.of("session/update", "h"),
						handle)
				.block();

			assertThat(logs.events()).anySatisfy(event -> assertThat(event.getFormattedMessage())
				.contains("session/update")
				.contains("-32603")
				.contains("Internal error"));
			logs.assertNoneAtInfoOrAboveContains("MESSAGE-SECRET");
			logs.assertNoneAtInfoOrAboveContains("DATA-SECRET");
		}
	}

}
