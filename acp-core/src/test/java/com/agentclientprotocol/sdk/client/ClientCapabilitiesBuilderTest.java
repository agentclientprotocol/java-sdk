/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.FileSystemCapability;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the clientCapabilities() builder method.
 *
 * <p>
 * These tests verify that the clientCapabilities set via the builder are properly
 * passed to the agent during initialization.
 * </p>
 *
 * @author Mark Pollack
 */
class ClientCapabilitiesBuilderTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private InMemoryTransportPair transportPair;

	@AfterEach
	void tearDown() {
		if (transportPair != null) {
			transportPair.closeGracefully().block(TIMEOUT);
			transportPair = null;
		}
	}

	/**
	 * Test that clientCapabilities set via the builder are sent to the agent.
	 * <p>
	 * BUG: Currently the clientCapabilities() builder method is ignored - the agent
	 * receives default capabilities instead of the custom ones configured.
	 */
	@Test
	void clientCapabilitiesBuilderMethodSendsCapabilitiesToAgent() throws Exception {
		transportPair = InMemoryTransportPair.create();

		AtomicReference<ClientCapabilities> receivedCapabilities = new AtomicReference<>();
		CountDownLatch capabilitiesLatch = new CountDownLatch(1);

		// Build agent that captures the client capabilities
		AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> {
				receivedCapabilities.set(request.clientCapabilities());
				capabilitiesLatch.countDown();
				return Mono.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of()));
			})
			.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn())).build();

		// Create custom client capabilities with file system and terminal enabled
		FileSystemCapability fsCaps = new FileSystemCapability(true, true);
		ClientCapabilities customCaps = new ClientCapabilities(fsCaps, true);

		// Build client with custom capabilities via builder
		AcpAsyncClient client = servingEveryCapability(AcpClient.async(transportPair.clientTransport()))
			.requestTimeout(TIMEOUT)
			.clientCapabilities(customCaps) // This should be sent to agent
			.build();

		// Start agent
		agent.start().subscribe();
		Thread.sleep(100);

		// Initialize client using no-arg method (should use builder capabilities)
		client.initialize().block(TIMEOUT);

		// Verify agent received our custom capabilities
		assertThat(capabilitiesLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(receivedCapabilities.get()).isNotNull();

		// BUG: These assertions currently FAIL because builder capabilities are ignored
		assertThat(receivedCapabilities.get().fs())
			.as("File system capabilities should be set from builder")
			.isNotNull();
		assertThat(receivedCapabilities.get().fs().readTextFile())
			.as("readTextFile should be true from builder")
			.isTrue();
		assertThat(receivedCapabilities.get().fs().writeTextFile())
			.as("writeTextFile should be true from builder")
			.isTrue();
		assertThat(receivedCapabilities.get().terminal())
			.as("terminal should be true from builder")
			.isTrue();

		// Cleanup
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	/**
	 * The builder is the one place a client's capabilities and info are set: there is no
	 * initialize overload that takes a request, so none can silently replace them.
	 */
	@Test
	void clientsHaveNoInitializeThatTakesARequest() {
		for (Class<?> client : List.of(AcpAsyncClient.class, AcpSyncClient.class)) {
			assertThat(Arrays.stream(client.getMethods())
				.filter(method -> method.getName().equals("initialize"))
				.flatMap(method -> Arrays.stream(method.getParameterTypes())))
				.as(client.getSimpleName() + ".initialize parameters")
				.doesNotContain(AcpSchema.InitializeRequest.class, ClientCapabilities.class);
		}
	}

	@Test
	void initializeSendsTheBuilderCapabilitiesAndClientInfo() throws Exception {
		transportPair = InMemoryTransportPair.create();
		AtomicReference<AcpSchema.InitializeRequest> received = new AtomicReference<>();
		AcpAsyncAgent agent = capturingAgent(received);
		ClientCapabilities caps = ClientCapabilities.builder()
			.session(AcpSchema.ClientSessionCapabilities.withBooleanConfigOptions())
			.build();
		AcpSchema.Implementation info = new AcpSchema.Implementation("my-client", "1.0");

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(caps)
			.clientInfo(info)
			.build();
		agent.start().subscribe();

		client.initialize().block(TIMEOUT);

		assertThat(received.get()).isEqualTo(new AcpSchema.InitializeRequest(1, caps, info, null));
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	@Test
	void initializeWithVersionAndMetaKeepsTheBuilderCapabilities() throws Exception {
		transportPair = InMemoryTransportPair.create();
		AtomicReference<AcpSchema.InitializeRequest> received = new AtomicReference<>();
		AcpAsyncAgent agent = capturingAgent(received);
		ClientCapabilities caps = new ClientCapabilities(new FileSystemCapability(true, true), true);

		AcpAsyncClient client = servingEveryCapability(AcpClient.async(transportPair.clientTransport()))
			.requestTimeout(TIMEOUT)
			.clientCapabilities(caps)
			.build();
		agent.start().subscribe();

		client.initialize(1, Map.<String, Object>of("trace", "t")).block(TIMEOUT);

		assertThat(received.get()).isEqualTo(new AcpSchema.InitializeRequest(1, caps, null, Map.of("trace", "t")));
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	@Test
	void syncClientSendsTheBuilderCapabilitiesAndClientInfo() throws Exception {
		transportPair = InMemoryTransportPair.create();
		AtomicReference<AcpSchema.InitializeRequest> received = new AtomicReference<>();
		AcpAsyncAgent agent = capturingAgent(received);
		ClientCapabilities caps = ClientCapabilities.builder().terminal(true).build();
		AcpSchema.Implementation info = new AcpSchema.Implementation("my-client", "1.0");

		AcpSyncClient client = AcpClient.sync(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(caps)
			.clientInfo(info)
			.createTerminalHandler(request -> null)
			.terminalOutputHandler(request -> null)
			.releaseTerminalHandler(request -> null)
			.waitForTerminalExitHandler(request -> null)
			.killTerminalHandler(request -> null)
			.build();
		agent.start().subscribe();

		client.initialize();
		client.initialize(1, Map.<String, Object>of("trace", "t"));

		assertThat(received.get()).isEqualTo(new AcpSchema.InitializeRequest(1, caps, info, Map.of("trace", "t")));
		client.closeGracefully();
		agent.closeGracefully().block(TIMEOUT);
	}

	/** A client advertising files and terminals must serve them, or it does not build. */
	private static AcpClient.AsyncSpec servingEveryCapability(AcpClient.AsyncSpec spec) {
		return spec.readTextFileHandler(request -> Mono.empty())
			.writeTextFileHandler(request -> Mono.empty())
			.createTerminalHandler(request -> Mono.empty())
			.terminalOutputHandler(request -> Mono.empty())
			.releaseTerminalHandler(request -> Mono.empty())
			.waitForTerminalExitHandler(request -> Mono.empty())
			.killTerminalHandler(request -> Mono.empty());
	}

	private AcpAsyncAgent capturingAgent(AtomicReference<AcpSchema.InitializeRequest> received) {
		return AcpAgent.async(transportPair.agentTransport()).requestTimeout(TIMEOUT).initializeHandler(request -> {
			received.set(request);
			return Mono.just(AcpSchema.InitializeResponse.ok());
		}).promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn())).build();
	}

}
