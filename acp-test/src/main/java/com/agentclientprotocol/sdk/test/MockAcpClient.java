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
 * A mock ACP client for testing agent implementations.
 *
 * <p>
 * This mock provides:
 * <ul>
 * <li>Automatic handling of agent→client requests (permissions, file ops)</li>
 * <li>Recording of all received session updates</li>
 * <li>Configurable response handlers</li>
 * <li>Synchronous waiting for specific operations</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * InMemoryTransportPair transportPair = InMemoryTransportPair.create();
 * MockAcpClient mockClient = MockAcpClient.builder(transportPair.clientTransport())
 *     .permissionResponse(request -> new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected("allow")))
 *     .fileContent("/path/to/file.txt", "file contents")
 *     .build();
 *
 * mockClient.initialize();
 * mockClient.newSession("/workspace");
 *
 * // Agent operations will automatically receive mock responses
 *
 * assertThat(mockClient.getReceivedUpdates()).isNotEmpty();
 * mockClient.close();
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
			// Mock client advertises all capabilities (file read, file write, terminal)
			.clientCapabilities(new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(true, true), true))
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
	 * Creates a builder for configuring the mock client.
	 * @param transport The client transport
	 * @return A new builder instance
	 */
	public static Builder builder(AcpClientTransport transport) {
		return new Builder(transport);
	}

	/**
	 * Creates a simple mock client with default handlers.
	 * @param transport The client transport
	 * @return A new mock client with default behavior
	 */
	public static MockAcpClient createDefault(AcpClientTransport transport) {
		return builder(transport).build();
	}

	/**
	 * Initializes the connection with the agent.
	 * Advertises all capabilities (file read, file write, terminal) by default.
	 * @return The initialize response
	 */
	public AcpSchema.InitializeResponse initialize() {
		return await(delegate.initialize());
	}

	/**
	 * Creates a new session with the specified working directory.
	 * @param cwd The working directory
	 * @return The new session response
	 */
	public AcpSchema.NewSessionResponse newSession(String cwd) {
		AcpSchema.NewSessionResponse response = await(
				delegate.newSession(new AcpSchema.NewSessionRequest(cwd, List.of())));
		this.currentSessionId = response.sessionId();
		return response;
	}

	/**
	 * Sends a text prompt to the current session.
	 * @param text The prompt text
	 * @return The prompt response
	 */
	public AcpSchema.PromptResponse prompt(String text) {
		return prompt(requireSessionId(), text);
	}

	/**
	 * Sends a prompt to a specific session.
	 * @param sessionId The session ID
	 * @param text The prompt text
	 * @return The prompt response
	 */
	public AcpSchema.PromptResponse prompt(String sessionId, String text) {
		return await(delegate.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent(text)))));
	}

	/**
	 * Cancels operations for the current session.
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
	 * Sets a latch to wait for a specific number of updates.
	 * @param count The number of updates to wait for
	 */
	public void expectUpdates(int count) {
		updateLatch = new CountDownLatch(count);
	}

	/**
	 * Waits for expected updates to be received.
	 * @param timeout The maximum time to wait
	 * @return true if all expected updates were received
	 * @throws InterruptedException if interrupted while waiting
	 */
	public boolean awaitUpdates(Duration timeout) throws InterruptedException {
		return updateLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	/**
	 * Gets all received session updates.
	 * @return The list of updates
	 */
	public List<AcpSchema.SessionNotification> getReceivedUpdates() {
		return List.copyOf(receivedUpdates);
	}

	/**
	 * Gets all received permission requests.
	 * @return The list of permission requests
	 */
	public List<AcpSchema.RequestPermissionRequest> getReceivedPermissionRequests() {
		return List.copyOf(receivedPermissionRequests);
	}

	/**
	 * Gets all received file read requests.
	 * @return The list of file read requests
	 */
	public List<AcpSchema.ReadTextFileRequest> getReceivedFileReadRequests() {
		return List.copyOf(receivedFileReadRequests);
	}

	/**
	 * Gets all received file write requests.
	 * @return The list of file write requests
	 */
	public List<AcpSchema.WriteTextFileRequest> getReceivedFileWriteRequests() {
		return List.copyOf(receivedFileWriteRequests);
	}

	/**
	 * Gets the current session ID.
	 * @return The session ID, or null if no session created
	 */
	public @Nullable String getCurrentSessionId() {
		return currentSessionId;
	}

	/**
	 * Returns the underlying async client.
	 * @return The async client
	 */
	public AcpAsyncClient async() {
		return delegate;
	}

	/**
	 * Closes the mock client gracefully.
	 */
	public void closeGracefully() {
		delegate.closeGracefully().block(timeout);
	}

	/**
	 * Closes the mock client immediately.
	 */
	public void close() {
		delegate.close();
	}

	/**
	 * Builder for MockAcpClient.
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
		 * Sets a function to handle permission requests.
		 * @param handler The permission handler
		 * @return This builder
		 */
		public Builder permissionResponse(
				Function<AcpSchema.RequestPermissionRequest, AcpSchema.RequestPermissionResponse> handler) {
			this.permissionHandler = handler;
			return this;
		}

		/**
		 * Sets a function to handle file read requests.
		 * @param handler The file read handler
		 * @return This builder
		 */
		public Builder readFileResponse(
				Function<AcpSchema.ReadTextFileRequest, AcpSchema.ReadTextFileResponse> handler) {
			this.readFileHandler = handler;
			return this;
		}

		/**
		 * Configures a specific file path to return specific content.
		 * @param path The file path
		 * @param content The file content
		 * @return This builder
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
		 * Sets a function to handle file write requests.
		 * @param handler The file write handler
		 * @return This builder
		 */
		public Builder writeFileResponse(
				Function<AcpSchema.WriteTextFileRequest, AcpSchema.WriteTextFileResponse> handler) {
			this.writeFileHandler = handler;
			return this;
		}

		/**
		 * Sets the request timeout.
		 * @param timeout The timeout
		 * @return This builder
		 */
		public Builder requestTimeout(Duration timeout) {
			this.requestTimeout = timeout;
			return this;
		}

		/**
		 * Builds the mock client.
		 * @return The configured mock client
		 */
		public MockAcpClient build() {
			return new MockAcpClient(this);
		}

	}

}
