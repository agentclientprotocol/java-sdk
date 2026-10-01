/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A transport carries exactly one session. Building a second client on a transport
 * that is already connected must fail at construction with a message that names the
 * cause, not produce a client whose first request times out.
 *
 * <p>
 * This is the shape of the Spring Boot autoconfiguration failure reported 2026-06-11:
 * the autoconfig built an {@link AcpAsyncClient} and an {@link AcpSyncClient} from the
 * same {@link StdioAcpClientTransport} bean. Each build constructed its own session,
 * each session called {@code connect()}, and the second {@code connect()} subscribed the
 * transport's unicast inbound sink a second time. Before the fix the only trace was a
 * dropped {@code Sinks.many().unicast() sinks only allow a single Subscriber} logged
 * from the session constructor, and the second client silently never received a
 * response.
 * </p>
 *
 * @author Mark Pollack
 */
class AcpClientSessionConnectTest {

	/**
	 * A process that starts on every platform and exits on its own: the running JVM,
	 * asked for its version. The transport only needs a process that starts.
	 */
	private static AgentParameters harmlessProcess() {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		return AgentParameters.builder(java).arg("-version").build();
	}

	@Test
	void secondClientOnAnAlreadyConnectedStdioTransportFailsAtConstruction() {
		StdioAcpClientTransport transport = new StdioAcpClientTransport(harmlessProcess());

		AcpAsyncClient first = AcpClient.async(transport).build();
		assertThat(first).isNotNull();

		try {
			assertThatThrownBy(() -> AcpClient.sync(transport).build()).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already connected");
		}
		finally {
			first.close();
		}
	}

	/**
	 * The supported shape when an application needs both APIs: one session, two facades.
	 * This is what the Spring Boot autoconfiguration should build instead of two clients.
	 */
	@Test
	void syncFacadeOverAnExistingAsyncClientSharesOneSession() throws Exception {
		Duration timeout = Duration.ofSeconds(10);
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.requestTimeout(timeout)
			.initializeHandler(request -> Mono.just(new AcpSchema.InitializeResponse(1,
					new AcpSchema.AgentCapabilities(true, null, null), List.of())))
			.build();
		agent.start().subscribe();

		AcpAsyncClient async = AcpClient.async(pair.clientTransport()).requestTimeout(timeout).build();
		AcpSyncClient sync = new AcpSyncClient(async);
		try {
			AcpSchema.InitializeResponse response = sync.initialize();
			assertThat(response.agentCapabilities().loadSession()).isTrue();
			assertThat(async.getAgentCapabilities()).isNotNull();
		}
		finally {
			sync.closeGracefully();
			agent.closeGracefully().block(timeout);
			pair.closeGracefully().block(timeout);
		}
	}

}
