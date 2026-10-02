/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnansweredRequestsTest {

	private final UnansweredRequests requests = new UnansweredRequests();

	@Test
	void theEndClaimsTheRequestsStillWaitingOnce() {
		assertThat(requests.sent("a-1")).isTrue();
		assertThat(requests.sent("a-2")).isTrue();
		requests.answered("a-1");

		assertThat(requests.end()).containsExactly("a-2");
		assertThat(requests.ended()).isTrue();
		assertThat(requests.end()).isEmpty();
	}

	@Test
	void aRequestSentAfterTheEndIsRefused() {
		assertThat(requests.ended()).isFalse();
		requests.end();

		assertThat(requests.sent("a-1")).isFalse();
		assertThat(requests.end()).isEmpty();
	}

	/** JSON-RPC ids compare by value: an int sent may come back as a long, and "1" is not 1. */
	@Test
	void idsCompareAsJsonRpcValues() {
		requests.sent(1);
		requests.sent("1");
		requests.answered(1L);

		assertThat(requests.end()).containsExactly("1");
	}

}
