/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A framework sets a default permission handler, for example one that refuses; the
 * application's own handler replaces it, whichever is registered first.
 */
class DefaultRequestPermissionHandlerTest {

	private static final AcpSchema.JSONRPCRequest REQUEST = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION,
			"p-1", AcpSchema.METHOD_SESSION_REQUEST_PERMISSION,
			Map.of("sessionId", "s-1", "toolCall", Map.of("toolCallId", "t-1"), "options",
					List.of(Map.of("optionId", "yes", "name", "Allow", "kind", "allow_once"))));

	@Test
	void theDefaultAnswersWhenTheApplicationRegistersNone() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport).defaultRequestPermissionHandler(answer("default")).build();

		transport.simulateIncomingMessage(REQUEST);

		assertThat(answeredWith(transport)).isEqualTo("default");
		client.close();
	}

	@Test
	void theApplicationsHandlerReplacesTheDefaultInEitherOrder() {
		MockAcpClientTransport before = new MockAcpClientTransport();
		AcpAsyncClient first = AcpClient.async(before)
			.defaultRequestPermissionHandler(answer("default"))
			.requestPermissionHandler(answer("app"))
			.build();
		before.simulateIncomingMessage(REQUEST);
		assertThat(answeredWith(before)).isEqualTo("app");
		first.close();

		MockAcpClientTransport after = new MockAcpClientTransport();
		AcpAsyncClient second = AcpClient.async(after)
			.requestPermissionHandler(answer("app"))
			.defaultRequestPermissionHandler(answer("default"))
			.build();
		after.simulateIncomingMessage(REQUEST);
		assertThat(answeredWith(after)).isEqualTo("app");
		second.close();
	}

	@Test
	void aLaterDefaultReplacesAnEarlierOne() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpAsyncClient client = AcpClient.async(transport)
			.defaultRequestPermissionHandler(answer("first"))
			.defaultRequestPermissionHandler(answer("second"))
			.build();

		transport.simulateIncomingMessage(REQUEST);

		assertThat(answeredWith(transport)).isEqualTo("second");
		assertThatThrownBy(() -> AcpClient.async(new MockAcpClientTransport()).defaultRequestPermissionHandler(null))
			.isInstanceOf(IllegalArgumentException.class);
		client.close();
	}

	private static java.util.function.Function<AcpSchema.RequestPermissionRequest, Mono<AcpSchema.RequestPermissionResponse>> answer(
			String optionId) {
		return request -> Mono
			.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected(optionId)));
	}

	/** The option id of the answer to {@code p-1}, waiting up to five seconds for it. */
	private static String answeredWith(MockAcpClientTransport transport) {
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (System.nanoTime() < deadline) {
			for (AcpSchema.JSONRPCMessage message : transport.getSentMessages()) {
				if (message instanceof AcpSchema.JSONRPCResponse response && "p-1".equals(response.id())
						&& response.result() instanceof AcpSchema.RequestPermissionResponse answer
						&& answer.outcome() instanceof AcpSchema.PermissionSelected selected) {
					return selected.optionId();
				}
			}
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new AssertionError("no answer to p-1 in " + transport.getSentMessages());
	}

}
