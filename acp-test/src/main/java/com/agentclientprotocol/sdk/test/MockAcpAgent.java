/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A scripted ACP agent for testing client code: it answers {@code initialize}, {@code session/new}
 * and {@code session/prompt} with the responses set on its {@link Builder}, and records every
 * request it receives so a test can check what the client sent. Put it on the agent side of an
 * {@link InMemoryTransportPair}, or on any {@link AcpAgentTransport}, and call {@link #start()}. To
 * test an agent instead, use {@link MockAcpClient}.
 *
 * <p>Without builder settings it answers {@code initialize} with protocol version 1, no agent
 * capabilities and no authentication methods, {@code session/new} with the session ID
 * {@code "mock-session"}, and every prompt with stop reason {@code end_turn}. It accepts
 * {@code session/cancel} and records it. Every other request is answered {@code -32601} (method not
 * found), even when a custom {@code initialize} answer advertises the capability. Send session
 * updates with {@link #sendSessionUpdate}; for other calls to the client, use the agent it wraps,
 * from {@link #async()}.
 *
 * <p>The {@code getReceived...} methods return copies of what has arrived so far, oldest first. To
 * wait for prompts, call {@link #expectPrompts(int)} before the client sends them, then
 * {@link #awaitPrompts(Duration)}. The methods may be called from any thread while the client runs.
 *
 * <pre>{@code
 * InMemoryTransportPair pair = InMemoryTransportPair.create();
 * MockAcpAgent agent = MockAcpAgent.builder(pair.agentTransport())
 *     .promptResponse(request -> AcpSchema.PromptResponse.refusal())
 *     .build();
 * agent.start();
 *
 * AcpSyncClient client = AcpClient.sync(pair.clientTransport()).build();
 * client.initialize();
 * String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
 *     .sessionId();                                       // "mock-session"
 * AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest(sessionId,
 *     List.of(new AcpSchema.TextContent("Delete everything"))));
 *
 * List<AcpSchema.PromptRequest> prompts = agent.getReceivedPrompts(); // the one prompt sent
 * client.close();
 * agent.close();
 * }</pre>
 *
 * @author Mark Pollack
 */
public class MockAcpAgent {

	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

	private final AcpAsyncAgent delegate;

	private final List<AcpSchema.InitializeRequest> receivedInitRequests = new CopyOnWriteArrayList<>();

	private final List<AcpSchema.NewSessionRequest> receivedNewSessionRequests = new CopyOnWriteArrayList<>();

	private final List<AcpSchema.PromptRequest> receivedPrompts = new CopyOnWriteArrayList<>();

	private final List<AcpSchema.CancelNotification> receivedCancellations = new CopyOnWriteArrayList<>();

	private final AtomicReference<CountDownLatch> promptLatch = new AtomicReference<>(new CountDownLatch(0));

	private MockAcpAgent(Builder builder) {
		AcpSchema.InitializeResponse initializeResponse = builder.initializeResponse;
		AcpSchema.NewSessionResponse newSessionResponse = builder.newSessionResponse;
		Function<AcpSchema.PromptRequest, AcpSchema.PromptResponse> promptResponseProvider = builder.promptResponseProvider;
		// The handlers record into this mock's fields, which are initialized before this body runs
		this.delegate = AcpAgent.async(builder.transport)
			.requestTimeout(builder.requestTimeout)
			.initializeHandler(request -> {
				receivedInitRequests.add(request);
				return Mono.just(initializeResponse);
			})
			.newSessionHandler(request -> {
				receivedNewSessionRequests.add(request);
				return Mono.just(newSessionResponse);
			})
			.promptHandler((request, updater) -> {
				receivedPrompts.add(request);
				promptLatch.get().countDown();
				return Mono.just(promptResponseProvider.apply(request));
			})
			.cancelHandler(notification -> {
				receivedCancellations.add(notification);
				return Mono.empty();
			})
			.build();
	}

	/**
	 * Starts a builder for a mock agent on the given transport, with the default responses.
	 * @param transport the agent transport, usually {@link InMemoryTransportPair#agentTransport()};
	 * must not be null
	 * @return a new builder
	 */
	public static Builder builder(AcpAgentTransport transport) {
		return new Builder(transport);
	}

	/**
	 * Creates a mock agent with the default responses (see the class comment), not yet started. The
	 * same as {@code builder(transport).build()}.
	 * @param transport the agent transport
	 * @return a new mock agent
	 * @throws IllegalArgumentException if {@code transport} is null
	 */
	public static MockAcpAgent createDefault(AcpAgentTransport transport) {
		return builder(transport).build();
	}

	/**
	 * Starts the agent, which starts its transport; from then on it answers the client. It returns
	 * once the agent is started and does not wait for the client. Call it once: a failure to start,
	 * such as a transport that was started before, is logged at WARN under this class's logger, not
	 * thrown.
	 */
	public void start() {
		delegate.start()
			.subscribe(ignored -> {
			}, error -> LoggerFactory.getLogger(MockAcpAgent.class).warn("Mock agent failed to start", error));
	}

	/**
	 * Sets how many prompts {@link #awaitPrompts} waits for, counting from this call: prompts that
	 * arrived before it do not count. Call it before the client sends them. Until it is called,
	 * {@code awaitPrompts} returns {@code true} at once.
	 * @param count the number of prompts to wait for
	 * @throws IllegalArgumentException if {@code count} is negative
	 */
	public void expectPrompts(int count) {
		promptLatch.set(new CountDownLatch(count));
	}

	/**
	 * Waits until the prompts set by the last {@link #expectPrompts} call have arrived, or the
	 * timeout passes. A prompt counts when it reaches the mock, before its answer is sent.
	 * @param timeout the longest to wait
	 * @return {@code true} if they arrived, {@code false} if the timeout passed first
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	public boolean awaitPrompts(Duration timeout) throws InterruptedException {
		return promptLatch.get().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	/**
	 * Returns the {@code initialize} requests received so far, oldest first.
	 * @return a copy, which later requests do not change
	 */
	public List<AcpSchema.InitializeRequest> getReceivedInitRequests() {
		return List.copyOf(receivedInitRequests);
	}

	/**
	 * Returns the {@code session/new} requests received so far, oldest first.
	 * @return a copy, which later requests do not change
	 */
	public List<AcpSchema.NewSessionRequest> getReceivedNewSessionRequests() {
		return List.copyOf(receivedNewSessionRequests);
	}

	/**
	 * Returns the {@code session/prompt} requests received so far, oldest first, from every
	 * session.
	 * @return a copy, which later prompts do not change
	 */
	public List<AcpSchema.PromptRequest> getReceivedPrompts() {
		return List.copyOf(receivedPrompts);
	}

	/**
	 * Returns the {@code session/cancel} notifications received so far, oldest first.
	 * @return a copy, which later notifications do not change
	 */
	public List<AcpSchema.CancelNotification> getReceivedCancellations() {
		return List.copyOf(receivedCancellations);
	}

	/**
	 * Sends a {@code session/update} notification to the client, during a prompt or between
	 * prompts, and blocks until it is handed to the transport, at most 10 seconds. The session ID
	 * is not checked.
	 * @param sessionId the ACP session the update belongs to
	 * @param update the update, such as an {@code AgentMessageChunk}
	 * @throws IllegalStateException if the agent is not started, or the send takes longer than 10
	 * seconds
	 */
	public void sendSessionUpdate(String sessionId, AcpSchema.SessionUpdate update) {
		delegate.sendSessionUpdate(sessionId, update).block(DEFAULT_TIMEOUT);
	}

	/**
	 * Returns the agent the mock wraps, for calls the mock does not offer: permission, file,
	 * terminal and extension requests to the client, or the client's capabilities.
	 * @return the underlying agent
	 */
	public AcpAsyncAgent async() {
		return delegate;
	}

	/**
	 * Closes the agent gracefully and blocks until its transport has closed, at most 10 seconds:
	 * requests still being handled are answered as cancelled ({@code -32800}). See
	 * {@link AcpAsyncAgent#closeGracefully()}.
	 * @throws IllegalStateException if closing takes longer than 10 seconds
	 */
	public void closeGracefully() {
		delegate.closeGracefully().block(DEFAULT_TIMEOUT);
	}

	/**
	 * Closes the agent and its transport at once, without waiting. See
	 * {@link AcpAsyncAgent#close()}.
	 */
	public void close() {
		delegate.close();
	}

	/**
	 * Configures a {@link MockAcpAgent}: its answers to {@code initialize}, {@code session/new} and
	 * {@code session/prompt}, and the timeout of its requests to the client. Every setting has a
	 * default (see {@link MockAcpAgent}). Get one from {@link MockAcpAgent#builder};
	 * {@link #build()} creates the mock, not yet started.
	 */
	public static class Builder {

		private final AcpAgentTransport transport;

		private AcpSchema.InitializeResponse initializeResponse;

		private AcpSchema.NewSessionResponse newSessionResponse;

		private Function<AcpSchema.PromptRequest, AcpSchema.PromptResponse> promptResponseProvider;

		private Duration requestTimeout = DEFAULT_TIMEOUT;

		private Builder(AcpAgentTransport transport) {
			this.transport = transport;
			// Set defaults
			this.initializeResponse = new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of());
			this.newSessionResponse = new AcpSchema.NewSessionResponse("mock-session", null, null);
			this.promptResponseProvider = request -> new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN);
		}

		/**
		 * Sets the answer to every {@code initialize} request. Capabilities it advertises are only
		 * advertised: the mock still serves only {@code initialize}, {@code session/new},
		 * {@code session/prompt} and {@code session/cancel}.
		 * @param response the answer; must not be null. Default: protocol version 1, no
		 * capabilities, no authentication methods
		 * @return this builder
		 */
		public Builder initializeResponse(AcpSchema.InitializeResponse response) {
			this.initializeResponse = response;
			return this;
		}

		/**
		 * Sets the answer to every {@code session/new} request, so every session gets the same ID.
		 * @param response the answer; must not be null. Default: session ID {@code "mock-session"},
		 * no modes and no config options
		 * @return this builder
		 */
		public Builder newSessionResponse(AcpSchema.NewSessionResponse response) {
			this.newSessionResponse = response;
			return this;
		}

		/**
		 * Sets the function that answers each prompt, given the request. It runs after the prompt
		 * is recorded. An exception it throws answers the prompt with an error: an
		 * {@code AcpProtocolException} with its own code, anything else with {@code -32603}
		 * (internal error).
		 * @param provider the function; must not be null or return null. Default: stop reason
		 * {@code end_turn}
		 * @return this builder
		 */
		public Builder promptResponse(Function<AcpSchema.PromptRequest, AcpSchema.PromptResponse> provider) {
			this.promptResponseProvider = provider;
			return this;
		}

		/**
		 * Sets how long the agent waits for the client to answer a request it sends, such as one
		 * made through {@link MockAcpAgent#async()}; see
		 * {@link AcpAgent.AsyncAgentBuilder#requestTimeout(Duration)}. It does not change how long
		 * {@link MockAcpAgent#sendSessionUpdate} and {@link MockAcpAgent#closeGracefully()} wait,
		 * which is 10 seconds.
		 * @param timeout the timeout; default 10 seconds
		 * @return this builder
		 */
		public Builder requestTimeout(Duration timeout) {
			this.requestTimeout = timeout;
			return this;
		}

		/**
		 * Builds the mock agent; call {@link MockAcpAgent#start()} to start it.
		 * @return the mock agent
		 * @throws IllegalArgumentException if the transport or the request timeout is null
		 */
		public MockAcpAgent build() {
			return new MockAcpAgent(this);
		}

	}

}
