/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.PinnedVirtualThreads;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link StreamableHttpInbound}, the ordered stream an SSE reader hands each
 * received message to.
 */
class StreamableHttpInboundTest {

	/**
	 * The SSE readers run on virtual threads on JDK 21+, and emitting runs the client's
	 * handler chain on the reader's thread. A park there must not pin the carrier: every
	 * carrier pinned on a lock whose owner waits for a carrier deadlocks the client.
	 */
	@Test
	void emitDoesNotPinTheVirtualThreadItRunsOn() throws Exception {
		AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();
		StreamableHttpInbound inbound = new StreamableHttpInbound(new StreamableHttpRoutes(jsonMapper),
				mock(StreamableHttpStreams.class), jsonMapper);
		Disposable subscription = inbound.messages().subscribe(message -> {
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		AcpSchema.JSONRPCNotification update = new AcpSchema.JSONRPCNotification(AcpSchema.METHOD_SESSION_UPDATE,
				Map.of("sessionId", "s1"));

		List<String> pinned = PinnedVirtualThreads
			.pinnedParks(() -> inbound.emit(update).block(Duration.ofSeconds(5)));

		assertThat(pinned).isEmpty();
		subscription.dispose();
	}

}
