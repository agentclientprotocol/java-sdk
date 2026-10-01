/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.CompleteElicitationNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.CreateElicitationRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.CreateElicitationResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ElicitationCapabilities;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Elicitation (stable since ACP v1.9.1): the agent asks, the client's typed handler
 * answers, URL-mode completion reaches the client's typed {@code elicitation/complete}
 * handler, and each side honours the modes the client advertised.
 */
class ElicitationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String SESSION = "session-elicit";

	private final InMemoryTransportPair pair = InMemoryTransportPair.create();

	@AfterEach
	void close() {
		pair.closeGracefully().block(TIMEOUT);
	}

	private AcpAsyncAgent startAgent() {
		AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono
				.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())))
			.build();
		agent.start().block(TIMEOUT);
		return agent;
	}

	private static AcpSchema.InitializeRequest initialize(ElicitationCapabilities elicitation) {
		return new AcpSchema.InitializeRequest(1,
				new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(), false, null, null, elicitation, null));
	}

	private static CreateElicitationRequest urlRequest(String elicitationId) {
		return CreateElicitationRequest.url(SESSION, "Authorize access", elicitationId,
				"https://agent.example.com/connect?elicitationId=" + elicitationId);
	}

	@Test
	void urlElicitationCompletionReachesTheAsyncClientsTypedHandler() throws Exception {
		AcpAsyncAgent agent = startAgent();
		CompletableFuture<CompleteElicitationNotification> completed = new CompletableFuture<>();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.createElicitationHandler(request -> {
				assertThat(request.mode()).isEqualTo(CreateElicitationRequest.MODE_URL);
				return Mono.just(CreateElicitationResponse.accept());
			})
			.completeElicitationHandler(notification -> Mono.fromRunnable(() -> completed.complete(notification)))
			.build();
		client.initialize(initialize(ElicitationCapabilities.urlOnly())).block(TIMEOUT);

		CreateElicitationResponse response = agent.createElicitation(urlRequest("github-oauth-001")).block(TIMEOUT);
		assertThat(response).isNotNull();
		assertThat(response.action()).isEqualTo(AcpSchema.ElicitationAction.ACCEPT);
		agent.completeElicitation(new CompleteElicitationNotification("github-oauth-001")).block(TIMEOUT);

		assertThat(completed.get(5, TimeUnit.SECONDS).elicitationId()).isEqualTo("github-oauth-001");
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	@Test
	void urlElicitationCompletionReachesTheSyncClientsTypedHandler() throws Exception {
		AcpAsyncAgent agent = startAgent();
		CompletableFuture<CompleteElicitationNotification> completed = new CompletableFuture<>();
		Function<CreateElicitationRequest, CreateElicitationResponse> create = request -> CreateElicitationResponse
			.accept();
		AcpSyncClient client = AcpClient.sync(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.createElicitationHandler(create)
			.completeElicitationHandler(completed::complete)
			.build();
		client.initialize(initialize(ElicitationCapabilities.formAndUrl()));

		AcpSyncAgent syncAgent = new AcpSyncAgent(agent, TIMEOUT);
		assertThat(syncAgent.createElicitation(urlRequest("e-sync")).action())
			.isEqualTo(AcpSchema.ElicitationAction.ACCEPT);
		syncAgent.completeElicitation(new CompleteElicitationNotification("e-sync", Map.of("k", "v")));

		CompleteElicitationNotification notification = completed.get(5, TimeUnit.SECONDS);
		assertThat(notification.elicitationId()).isEqualTo("e-sync");
		assertThat(notification.meta()).containsEntry("k", "v");
		client.close();
		agent.closeGracefully().block(TIMEOUT);
	}

	@Test
	void agentRefusesAModeTheClientDidNotAdvertise() {
		AcpAsyncAgent agent = startAgent();
		AtomicInteger asked = new AtomicInteger();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.createElicitationHandler(request -> {
				asked.incrementAndGet();
				return Mono.just(CreateElicitationResponse.decline());
			})
			.build();
		client.initialize(initialize(ElicitationCapabilities.formOnly())).block(TIMEOUT);

		assertThatThrownBy(() -> agent.createElicitation(urlRequest("e1")).block(TIMEOUT))
			.isInstanceOf(AcpCapabilityException.class)
			.hasMessageContaining("elicitation.url");
		CreateElicitationResponse form = agent
			.createElicitation(CreateElicitationRequest.form(SESSION, "Name?",
					new AcpSchema.ElicitationSchema(Map.of("name", AcpSchema.StringPropertySchema.text("Name")),
							List.of("name"))))
			.block(TIMEOUT);
		assertThat(form).isNotNull();
		assertThat(form.action()).isEqualTo(AcpSchema.ElicitationAction.DECLINE);
		assertThat(asked).hasValue(1);
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	@Test
	void anEmptyElicitationCapabilityAdvertisesNoMode() {
		AcpAsyncAgent agent = startAgent();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		client.initialize(initialize(new ElicitationCapabilities(null, null, null))).block(TIMEOUT);

		assertThatThrownBy(() -> agent
			.createElicitation(CreateElicitationRequest.form(SESSION, "Name?",
					new AcpSchema.ElicitationSchema(Map.of(), null)))
			.block(TIMEOUT)).isInstanceOf(AcpCapabilityException.class).hasMessageContaining("elicitation.form");
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	@Test
	void clientAnswersInvalidParamsForAModeItDidNotAdvertise() throws Exception {
		CompletableFuture<AcpSchema.JSONRPCResponse> answer = new CompletableFuture<>();
		MockAcpClientTransport transport = new MockAcpClientTransport((t, message) -> {
			if (message instanceof AcpSchema.JSONRPCResponse response) {
				answer.complete(response);
			}
		});
		AtomicInteger asked = new AtomicInteger();
		AcpAsyncClient client = AcpClient.async(transport).createElicitationHandler(request -> {
			asked.incrementAndGet();
			return Mono.just(CreateElicitationResponse.cancel());
		}).build();
		client.initialize(initialize(ElicitationCapabilities.formOnly())).subscribe(r -> {
		}, e -> {
		});

		transport.simulateIncomingMessage(new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "e-1",
				AcpSchema.METHOD_ELICITATION_CREATE, Map.of("sessionId", SESSION, "mode", "url", "message", "Authorize",
						"elicitationId", "e1", "url", "https://agent.example.com/connect")));

		AcpSchema.JSONRPCResponse response = answer.get(5, TimeUnit.SECONDS);
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(AcpErrorCodes.INVALID_PARAMS);
		assertThat(asked).hasValue(0);
		client.closeGracefully().block(TIMEOUT);
	}

	@Test
	void clientPassesAnAdvertisedModeToItsHandler() throws Exception {
		CompletableFuture<AcpSchema.JSONRPCResponse> answer = new CompletableFuture<>();
		MockAcpClientTransport transport = new MockAcpClientTransport((t, message) -> {
			if (message instanceof AcpSchema.JSONRPCResponse response) {
				answer.complete(response);
			}
		});
		AcpAsyncClient client = AcpClient.async(transport)
			.createElicitationHandler(request -> Mono.just(CreateElicitationResponse.accept(Map.of("name", "Ada"))))
			.build();
		client.initialize(initialize(ElicitationCapabilities.formOnly())).subscribe(r -> {
		}, e -> {
		});

		transport.simulateIncomingMessage(new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "e-2",
				AcpSchema.METHOD_ELICITATION_CREATE,
				Map.of("requestId", 12, "mode", "form", "message", "Name?", "requestedSchema", Map.of())));

		AcpSchema.JSONRPCResponse response = answer.get(5, TimeUnit.SECONDS);
		assertThat(response.error()).isNull();
		assertThat(response.result()).isInstanceOf(CreateElicitationResponse.class);
		client.closeGracefully().block(TIMEOUT);
	}

	@Test
	void aModeTheSdkDoesNotKnowIsLeftToTheHandler() {
		AcpAsyncAgent agent = startAgent();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport())
			.requestTimeout(TIMEOUT)
			.createElicitationHandler(request -> Mono.just(request.mode().equals("x-future")
					? CreateElicitationResponse.decline() : CreateElicitationResponse.cancel()))
			.build();
		client.initialize(initialize(ElicitationCapabilities.formOnly())).block(TIMEOUT);

		CreateElicitationResponse response = agent
			.createElicitation(new CreateElicitationRequest(SESSION, null, null, "?", "x-future", null, null, null,
					null))
			.block(TIMEOUT);
		assertThat(response).isNotNull();
		assertThat(response.action()).isEqualTo(AcpSchema.ElicitationAction.DECLINE);
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	@Test
	void beforeInitializeTheClientKnowsNoModeAndAsksItsHandler() throws Exception {
		CompletableFuture<AcpSchema.JSONRPCResponse> answer = new CompletableFuture<>();
		MockAcpClientTransport transport = new MockAcpClientTransport((t, message) -> {
			if (message instanceof AcpSchema.JSONRPCResponse response) {
				answer.complete(response);
			}
		});
		AcpAsyncClient client = AcpClient.async(transport)
			.createElicitationHandler(request -> Mono.just(CreateElicitationResponse.decline()))
			.build();

		transport.simulateIncomingMessage(new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "e-3",
				AcpSchema.METHOD_ELICITATION_CREATE, Map.of("sessionId", SESSION, "mode", "url", "message", "Authorize",
						"elicitationId", "e3", "url", "https://agent.example.com/connect")));

		assertThat(answer.get(5, TimeUnit.SECONDS).error()).isNull();
		client.closeGracefully().block(TIMEOUT);
	}

	@Test
	void initializingWithoutCapabilitiesAdvertisesNoMode() throws Exception {
		CompletableFuture<AcpSchema.JSONRPCResponse> answer = new CompletableFuture<>();
		MockAcpClientTransport transport = new MockAcpClientTransport((t, message) -> {
			if (message instanceof AcpSchema.JSONRPCResponse response) {
				answer.complete(response);
			}
		});
		AcpAsyncClient client = AcpClient.async(transport)
			.requestHandler(AcpSchema.METHOD_ELICITATION_CREATE, params -> Mono.just("raw handler replaced"))
			.createElicitationHandler(request -> Mono.just(CreateElicitationResponse.decline()))
			.build();
		client.initialize(new AcpSchema.InitializeRequest(1, null)).subscribe(r -> {
		}, e -> {
		});

		transport.simulateIncomingMessage(new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "e-4",
				AcpSchema.METHOD_ELICITATION_CREATE, Map.of("sessionId", SESSION, "mode", "form", "message", "Name?",
						"requestedSchema", Map.of())));

		AcpSchema.JSONRPCResponse response = answer.get(5, TimeUnit.SECONDS);
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(AcpErrorCodes.INVALID_PARAMS);
		client.closeGracefully().block(TIMEOUT);
	}

	@Test
	void aRawRequestHandlerRegisteredLaterReplacesTheTypedOne() throws Exception {
		CompletableFuture<AcpSchema.JSONRPCResponse> answer = new CompletableFuture<>();
		MockAcpClientTransport transport = new MockAcpClientTransport((t, message) -> {
			if (message instanceof AcpSchema.JSONRPCResponse response) {
				answer.complete(response);
			}
		});
		AcpAsyncClient client = AcpClient.async(transport)
			.createElicitationHandler(request -> Mono.just(CreateElicitationResponse.decline()))
			.requestHandler(AcpSchema.METHOD_ELICITATION_CREATE, params -> Mono.just("raw"))
			.build();

		transport.simulateIncomingMessage(new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "e-5",
				AcpSchema.METHOD_ELICITATION_CREATE, Map.of("sessionId", SESSION, "mode", "form", "message", "Name?",
						"requestedSchema", Map.of())));

		assertThat(answer.get(5, TimeUnit.SECONDS).result()).isEqualTo("raw");
		client.closeGracefully().block(TIMEOUT);
	}

}
