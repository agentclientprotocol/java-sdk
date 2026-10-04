/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * A scripted ACP client for testing agent code: it answers the agent's permission and file requests
 * with the responses set on its {@link Builder}, records those requests and every session update it
 * receives, and drives the agent with blocking calls ({@link #initialize()},
 * {@link #newSession(String)}, {@link #prompt(String)}). Put it on the client side of an
 * {@link InMemoryTransportPair}, or on any {@link AcpClientTransport}. To test a client instead,
 * use {@link MockAcpAgent}.
 *
 * <p>How it differs from {@link MockAcpAgent}: it needs no start, because building it connects its
 * transport. It advertises file reading and writing and nothing else, so on the agent side a
 * terminal or elicitation call fails with
 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without being sent, and an
 * extension request is answered {@code -32601} (method not found). Without builder settings it
 * answers every permission request with the option ID {@code "allow"}, whatever options the agent
 * offered; every file read with the content {@code "// Mock file content"}; and every file write
 * with an empty answer, writing nothing. Its blocking calls, prompts included, wait at most the
 * builder's request timeout, 10 seconds by default.
 *
 * <p>When {@link #prompt(String)} returns, the turn's session updates have all been received,
 * because the client hands them over before the prompt's answer, so a test can read
 * {@link #getReceivedUpdates()} at once. To wait for updates sent between prompts, call
 * {@link #expectUpdates(int)} before they are sent, then {@link #awaitUpdates(Duration)}. The
 * {@code getReceived...} methods return copies, oldest first, and all methods may be called from
 * any thread.
 *
 * <pre>{@code
 * InMemoryTransportPair pair = InMemoryTransportPair.create();
 * AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
 *     .initializeHandler(request -> AcpSchema.InitializeResponse.ok())
 *     .newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1", null, null))
 *     .promptHandler((request, context) -> {
 *         String source = context.readFile("/src/Main.java");
 *         context.sendMessage("Main.java has " + source.length() + " characters");
 *         return AcpSchema.PromptResponse.endTurn();
 *     })
 *     .build();
 * agent.start();
 *
 * MockAcpClient client = MockAcpClient.builder(pair.clientTransport())
 *     .fileContent("/src/Main.java", "class Main {}")
 *     .build();
 * client.initialize();
 * client.newSession("/workspace");
 * AcpSchema.PromptResponse response = client.prompt("Count the characters");
 *
 * List<AcpSchema.SessionNotification> updates = client.getReceivedUpdates(); // the agent's message
 * List<AcpSchema.ReadTextFileRequest> reads = client.getReceivedFileReadRequests(); // one read
 * client.close();
 * agent.close();
 * }</pre>
 *
 * @author Mark Pollack
 */
public class MockAcpClient {

	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

	private final AcpAsyncClient delegate;

	private final Duration timeout;

	private final List<AcpSchema.SessionNotification> receivedUpdates = new CopyOnWriteArrayList<>();

	private final List<AcpSchema.RequestPermissionRequest> receivedPermissionRequests = new CopyOnWriteArrayList<>();

	private final List<AcpSchema.ReadTextFileRequest> receivedFileReadRequests = new CopyOnWriteArrayList<>();

	private final List<AcpSchema.WriteTextFileRequest> receivedFileWriteRequests = new CopyOnWriteArrayList<>();

	private volatile CountDownLatch updateLatch = new CountDownLatch(0);

	private volatile @Nullable String currentSessionId;

	private MockAcpClient(Builder builder) {
		this.timeout = builder.requestTimeout;
		Function<AcpSchema.RequestPermissionRequest, AcpSchema.RequestPermissionResponse> permissionHandler = builder.permissionHandler;
		Function<AcpSchema.ReadTextFileRequest, AcpSchema.ReadTextFileResponse> readFileHandler = builder.readFileHandler;
		Function<AcpSchema.WriteTextFileRequest, AcpSchema.WriteTextFileResponse> writeFileHandler = builder.writeFileHandler;
		// The handlers record into this mock's fields, which are initialized before this body runs
		this.delegate = AcpClient.async(builder.transport)
			// The mock client advertises what it has handlers for: file read and write. It has no
			// terminal handlers, so it does not advertise terminal.
			.clientCapabilities(new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(true, true), false))
			.requestTimeout(builder.requestTimeout)
			.sessionUpdateConsumer(notification -> {
				receivedUpdates.add(notification);
				updateLatch.countDown();
				return Mono.empty();
			})
			// Using typed handlers (no manual unmarshalling needed)
			.requestPermissionHandler((AcpSchema.RequestPermissionRequest request) -> {
				receivedPermissionRequests.add(request);
				return Mono.just(permissionHandler.apply(request));
			})
			.readTextFileHandler((AcpSchema.ReadTextFileRequest request) -> {
				receivedFileReadRequests.add(request);
				return Mono.just(readFileHandler.apply(request));
			})
			.writeTextFileHandler((AcpSchema.WriteTextFileRequest request) -> {
				receivedFileWriteRequests.add(request);
				return Mono.just(writeFileHandler.apply(request));
			})
			.build();
	}

	/**
	 * Starts a builder for a mock client on the given transport, with the default answers.
	 * @param transport the client transport, usually
	 * {@link InMemoryTransportPair#clientTransport()}; must not be null
	 * @return a new builder
	 */
	public static Builder builder(AcpClientTransport transport) {
		return new Builder(transport);
	}

	/**
	 * Creates a mock client with the default answers (see the class comment) and connects its
	 * transport. The same as {@code builder(transport).build()}.
	 * @param transport the client transport
	 * @return a new mock client
	 * @throws IllegalArgumentException if {@code transport} is null
	 * @throws IllegalStateException if the transport was connected before
	 */
	public static MockAcpClient createDefault(AcpClientTransport transport) {
		return builder(transport).build();
	}

	/**
	 * Sends {@code initialize} and blocks for the agent's answer. The client advertises file
	 * reading and writing, which the mock handles, and no terminal or elicitation.
	 * @return the agent's answer
	 * @throws com.agentclientprotocol.sdk.spec.AcpError if the agent answers with an error
	 */
	public AcpSchema.InitializeResponse initialize() {
		return await(delegate.initialize());
	}

	/**
	 * Creates a session in the given working directory, with no MCP servers, and makes it the
	 * current session for {@link #prompt(String)} and {@link #cancel()}.
	 * @param cwd the working directory, an absolute path in ACP
	 * @return the agent's answer, with the session ID
	 * @throws IllegalStateException if {@link #initialize()} has not been answered
	 * @throws com.agentclientprotocol.sdk.spec.AcpError if the agent answers with an error
	 */
	public AcpSchema.NewSessionResponse newSession(String cwd) {
		AcpSchema.NewSessionResponse response = await(
				delegate.newSession(new AcpSchema.NewSessionRequest(cwd, List.of())));
		this.currentSessionId = response.sessionId();
		return response;
	}

	/**
	 * Sends a text prompt to the current session and blocks until the agent answers it, at the end
	 * of the turn.
	 * @param text the prompt text
	 * @return the agent's answer, with the stop reason
	 * @throws IllegalStateException if {@link #newSession(String)} has not been called, or no
	 * answer arrives within the request timeout
	 * @throws com.agentclientprotocol.sdk.spec.AcpError if the agent answers with an error
	 */
	public AcpSchema.PromptResponse prompt(String text) {
		return prompt(requireSessionId(), text);
	}

	/**
	 * Sends a text prompt to the given session and blocks until the agent answers it, at the end of
	 * the turn. When the request timeout passes first, the call fails and the client sends the
	 * agent {@code $/cancel_request}, which makes a Java agent cancel the turn. It does not change
	 * the current session.
	 * @param sessionId the ACP session to prompt
	 * @param text the prompt text
	 * @return the agent's answer, with the stop reason
	 * @throws IllegalStateException if {@link #initialize()} has not been answered, or no answer
	 * arrives within the request timeout
	 * @throws com.agentclientprotocol.sdk.spec.AcpError if the agent answers with an error
	 */
	public AcpSchema.PromptResponse prompt(String sessionId, String text) {
		return await(delegate.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent(text)))));
	}

	/**
	 * Sends {@code session/cancel} for the current session and returns once it is handed to the
	 * transport. The agent then ends the turn and answers the waiting prompt, normally with stop
	 * reason {@code cancelled}. Since {@link #prompt(String)} blocks, call this from another
	 * thread.
	 * @throws IllegalStateException if {@link #newSession(String)} has not been called
	 */
	public void cancel() {
		delegate.cancel(new AcpSchema.CancelNotification(requireSessionId())).block(timeout);
	}

	private String requireSessionId() {
		String sessionId = this.currentSessionId;
		if (sessionId == null) {
			throw new IllegalStateException("No session created. Call newSession() first.");
		}
		return sessionId;
	}

	/** Blocks for a response; a request's Mono emits its response or fails, never completes empty. */
	private <T> T await(Mono<T> response) {
		T value = response.block(timeout);
		if (value == null) {
			throw new IllegalStateException("ACP request completed without a response");
		}
		return value;
	}

	/**
	 * Sets how many session updates {@link #awaitUpdates} waits for, counting from this call:
	 * updates that arrived before it do not count. Call it before the agent sends them. Until it is
	 * called, {@code awaitUpdates} returns {@code true} at once.
	 * @param count the number of updates to wait for
	 * @throws IllegalArgumentException if {@code count} is negative
	 */
	public void expectUpdates(int count) {
		updateLatch = new CountDownLatch(count);
	}

	/**
	 * Waits until the session updates set by the last {@link #expectUpdates} call have arrived, or
	 * the timeout passes.
	 * @param timeout the longest to wait
	 * @return {@code true} if they arrived, {@code false} if the timeout passed first
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	public boolean awaitUpdates(Duration timeout) throws InterruptedException {
		return updateLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	/**
	 * Returns the {@code session/update} notifications received so far, oldest first, from every
	 * session.
	 * @return a copy, which later updates do not change
	 */
	public List<AcpSchema.SessionNotification> getReceivedUpdates() {
		return List.copyOf(receivedUpdates);
	}

	/**
	 * Returns the {@code session/request_permission} requests received so far, oldest first.
	 * @return a copy, which later requests do not change
	 */
	public List<AcpSchema.RequestPermissionRequest> getReceivedPermissionRequests() {
		return List.copyOf(receivedPermissionRequests);
	}

	/**
	 * Returns the {@code fs/read_text_file} requests received so far, oldest first.
	 * @return a copy, which later requests do not change
	 */
	public List<AcpSchema.ReadTextFileRequest> getReceivedFileReadRequests() {
		return List.copyOf(receivedFileReadRequests);
	}

	/**
	 * Returns the {@code fs/write_text_file} requests received so far, oldest first, with the
	 * content the agent wanted written.
	 * @return a copy, which later requests do not change
	 */
	public List<AcpSchema.WriteTextFileRequest> getReceivedFileWriteRequests() {
		return List.copyOf(receivedFileWriteRequests);
	}

	/**
	 * Returns the ID of the session the last {@link #newSession(String)} call created.
	 * @return the session ID, or {@code null} before the first {@code newSession}
	 */
	public @Nullable String getCurrentSessionId() {
		return currentSessionId;
	}

	/**
	 * Returns the client the mock wraps, for calls the mock does not offer: loading or resuming a
	 * session, config options, extension methods, or prompts with other content. Sessions created
	 * through it do not change the current session.
	 * @return the underlying client
	 */
	public AcpAsyncClient async() {
		return delegate;
	}

	/**
	 * Closes the client gracefully and blocks until its transport has closed, at most the request
	 * timeout. See {@link AcpAsyncClient#closeGracefully()}.
	 * @throws IllegalStateException if closing takes longer than the request timeout
	 */
	public void closeGracefully() {
		delegate.closeGracefully().block(timeout);
	}

	/**
	 * Closes the client and its transport at once, without waiting. See
	 * {@link AcpAsyncClient#close()}.
	 */
	public void close() {
		delegate.close();
	}

	/**
	 * Configures a {@link MockAcpClient}: its answers to the agent's permission and file requests,
	 * and how long its calls wait. Every setting has a default (see {@link MockAcpClient}). Get one
	 * from {@link MockAcpClient#builder}; {@link #build()} creates the mock and connects its
	 * transport.
	 */
	public static class Builder {

		private final AcpClientTransport transport;

		private Function<AcpSchema.RequestPermissionRequest, AcpSchema.RequestPermissionResponse> permissionHandler;

		private Function<AcpSchema.ReadTextFileRequest, AcpSchema.ReadTextFileResponse> readFileHandler;

		private Function<AcpSchema.WriteTextFileRequest, AcpSchema.WriteTextFileResponse> writeFileHandler;

		private Duration requestTimeout = DEFAULT_TIMEOUT;

		private Builder(AcpClientTransport transport) {
			this.transport = transport;
			// Set defaults
			this.permissionHandler = request -> new AcpSchema.RequestPermissionResponse(
					new AcpSchema.PermissionSelected("allow"));
			this.readFileHandler = request -> new AcpSchema.ReadTextFileResponse("// Mock file content");
			this.writeFileHandler = request -> new AcpSchema.WriteTextFileResponse();
		}

		/**
		 * Sets the function that answers each {@code session/request_permission}, given the
		 * request; it runs after the request is recorded. To choose one of the offered options,
		 * read them from {@code request.options()}. The default, option ID {@code "allow"}, matches
		 * the option that
		 * {@link com.agentclientprotocol.sdk.agent.PromptContext#askPermission(String) PromptContext.askPermission}
		 * offers, so that returns {@code true}; {@code askChoice}, whose options are numbered,
		 * fails on it. An exception the function throws answers the agent with an error: an
		 * {@code AcpProtocolException} with its own code, anything else with {@code -32603}
		 * (internal error).
		 * @param handler the function; must not be null or return null
		 * @return this builder
		 */
		public Builder permissionResponse(
				Function<AcpSchema.RequestPermissionRequest, AcpSchema.RequestPermissionResponse> handler) {
			this.permissionHandler = handler;
			return this;
		}

		/**
		 * Sets the function that answers every {@code fs/read_text_file} request, replacing the
		 * default and any {@link #fileContent} set before.
		 * @param handler the function; must not be null or return null. Default: the content
		 * {@code "// Mock file content"} for every path
		 * @return this builder
		 */
		public Builder readFileResponse(
				Function<AcpSchema.ReadTextFileRequest, AcpSchema.ReadTextFileResponse> handler) {
			this.readFileHandler = handler;
			return this;
		}

		/**
		 * Answers reads of one path with the given content; reads of other paths go to the answer
		 * set before (the default, {@link #readFileResponse}, or earlier {@code fileContent}
		 * calls). The path is compared exactly as the agent sends it, and the whole content is
		 * returned whatever line and limit the request asks for. A later {@code readFileResponse}
		 * replaces it, so call that first.
		 * @param path the file path
		 * @param content the content to return
		 * @return this builder
		 */
		public Builder fileContent(String path, String content) {
			Function<AcpSchema.ReadTextFileRequest, AcpSchema.ReadTextFileResponse> previous = this.readFileHandler;
			this.readFileHandler = request -> {
				if (path.equals(request.path())) {
					return new AcpSchema.ReadTextFileResponse(content);
				}
				return previous.apply(request);
			};
			return this;
		}

		/**
		 * Sets the function that answers every {@code fs/write_text_file} request. The mock writes
		 * no file; the requests are recorded.
		 * @param handler the function; must not be null or return null. Default: an empty answer
		 * @return this builder
		 */
		public Builder writeFileResponse(
				Function<AcpSchema.WriteTextFileRequest, AcpSchema.WriteTextFileResponse> handler) {
			this.writeFileHandler = handler;
			return this;
		}

		/**
		 * Sets how long the mock's blocking calls wait for an answer, prompts included, and the
		 * client's request timeout ({@link AcpClient.AsyncSpec#requestTimeout(Duration)}). A call
		 * that gets no answer in time fails with an unchecked exception:
		 * {@link IllegalStateException} when the mock's own wait ends first, as it does for a
		 * prompt, or Reactor's wrapper around the client's
		 * {@link java.util.concurrent.TimeoutException}.
		 * @param timeout the timeout; default 10 seconds
		 * @return this builder
		 */
		public Builder requestTimeout(Duration timeout) {
			this.requestTimeout = timeout;
			return this;
		}

		/**
		 * Builds the mock client and connects its transport.
		 * @return the mock client
		 * @throws IllegalArgumentException if the transport or the request timeout is null
		 * @throws IllegalStateException if the transport was connected before
		 */
		public MockAcpClient build() {
			return new MockAcpClient(this);
		}

	}

}
