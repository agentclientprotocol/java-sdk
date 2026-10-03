/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.CreateTerminalResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ReleaseTerminalResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt context's helpers ({@code execute}, {@code askChoice}, {@code askPermission},
 * {@code tryReadFile}) against a real client over an in-memory transport.
 */
class PromptContextHelpersTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final InMemoryTransportPair transportPair = InMemoryTransportPair.create();

	@AfterEach
	void tearDown() {
		this.transportPair.closeGracefully().block(TIMEOUT);
	}

	private void connect(AcpAsyncClient client) {
		client.initialize().block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT);
	}

	private static PromptRequest prompt() {
		return new PromptRequest("s1", List.of(new TextContent("go")));
	}

	/**
	 * ACP terminals.mdx: "The Agent MUST release the terminal using terminal/release when it's no
	 * longer needed". A prompt cancelled while {@code execute} waits for the command to exit still
	 * releases the terminal it created.
	 */
	@Test
	void executeCancelledWhileWaitingForExitReleasesTheTerminal_terminalsMdxMustRelease() throws Exception {
		CountDownLatch created = new CountDownLatch(1);
		CountDownLatch released = new CountDownLatch(1);

		AcpAsyncAgent agent = AcpAgent.async(this.transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> Mono.just(InitializeResponse.ok()))
			.newSessionHandler(req -> Mono.just(new NewSessionResponse("s1", null, null)))
			.promptHandler((request, context) -> context.execute("sleep", "600").thenReturn(PromptResponse.endTurn()))
			.build();

		AcpAsyncClient client = AcpClient.async(this.transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(new ClientCapabilities(null, true))
			.createTerminalHandler(req -> {
				created.countDown();
				return Mono.just(new CreateTerminalResponse("term-1"));
			})
			.waitForTerminalExitHandler(req -> Mono.never())
			.terminalOutputHandler(req -> Mono.never())
			.releaseTerminalHandler(req -> {
				released.countDown();
				return Mono.just(new ReleaseTerminalResponse());
			})
			.build();

		agent.start().block(TIMEOUT);
		connect(client);

		Disposable turn = client.prompt(prompt()).subscribe(r -> {
		}, e -> {
		});
		assertThat(created.await(5, TimeUnit.SECONDS)).as("terminal/create").isTrue();
		// Give terminal/wait_for_exit time to be sent, then give up on the prompt: the client sends
		// $/cancel_request and the agent cancels the prompt handler.
		Thread.sleep(200);
		turn.dispose();

		assertThat(released.await(5, TimeUnit.SECONDS)).as("terminal/release after the cancel").isTrue();

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	/** The same for a sync handler: cancelling the prompt interrupts it in {@code execute}. */
	@Test
	void syncExecuteCancelledWhileWaitingForExitReleasesTheTerminal_terminalsMdxMustRelease() throws Exception {
		CountDownLatch created = new CountDownLatch(1);
		CountDownLatch released = new CountDownLatch(1);

		AcpSyncAgent agent = AcpAgent.sync(this.transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("s1", null, null))
			.promptHandler((request, context) -> {
				context.execute("sleep", "600");
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = terminalClient(created, released);

		agent.start();
		connect(client);

		Disposable turn = client.prompt(prompt()).subscribe(r -> {
		}, e -> {
		});
		assertThat(created.await(5, TimeUnit.SECONDS)).as("terminal/create").isTrue();
		Thread.sleep(200);
		turn.dispose();

		assertThat(released.await(5, TimeUnit.SECONDS)).as("terminal/release after the cancel").isTrue();

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	/**
	 * A client that answers {@code askChoice} with an option ID it was not offered gets a clear
	 * protocol error, not a {@code NumberFormatException} or {@code ArrayIndexOutOfBoundsException}.
	 */
	@Test
	void askChoiceAnsweredWithAnOptionThatWasNotOfferedFailsWithAProtocolError() {
		for (String answer : List.of("7", "-1", "yes")) {
			AtomicReference<Throwable> failure = new AtomicReference<>();
			InMemoryTransportPair pair = InMemoryTransportPair.create();
			AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
				.requestTimeout(TIMEOUT)
				.initializeHandler(req -> Mono.just(InitializeResponse.ok()))
				.newSessionHandler(req -> Mono.just(new NewSessionResponse("s1", null, null)))
				.promptHandler((request, context) -> context.askChoice("Which?", "red", "green")
					.doOnError(failure::set)
					.onErrorResume(e -> Mono.empty())
					.thenReturn(PromptResponse.endTurn()))
				.build();
			AcpAsyncClient client = AcpClient.async(pair.clientTransport())
				.requestTimeout(TIMEOUT)
				.requestPermissionHandler(req -> Mono.just(
						new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected(answer))))
				.build();
			try {
				agent.start().block(TIMEOUT);
				connect(client);
				client.prompt(prompt()).block(TIMEOUT);

				assertThat(failure.get()).as("answer %s", answer)
					.isInstanceOf(AcpProtocolException.class)
					.hasMessageContaining(answer);
			}
			finally {
				client.closeGracefully().block(TIMEOUT);
				agent.closeGracefully().block(TIMEOUT);
				pair.closeGracefully().block(TIMEOUT);
			}
		}
	}

	@Test
	void askChoiceReturnsTheChosenOption() {
		AcpAsyncAgent agent = AcpAgent.async(this.transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> Mono.just(InitializeResponse.ok()))
			.newSessionHandler(req -> Mono.just(new NewSessionResponse("s1", null, null)))
			.promptHandler((request, context) -> context.askChoice("Which?", "red", "green")
				.flatMap(context::sendMessage)
				.thenReturn(PromptResponse.endTurn()))
			.build();
		List<String> messages = new CopyOnWriteArrayList<>();
		AcpAsyncClient client = AcpClient.async(this.transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.requestPermissionHandler(req -> Mono
				.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected("1"))))
			.sessionUpdateConsumer(n -> Mono.fromRunnable(() -> {
				if (n.update() instanceof AcpSchema.AgentMessageChunk chunk) {
					messages.add(((TextContent) chunk.content()).text());
				}
			}))
			.build();
		agent.start().block(TIMEOUT);
		connect(client);
		client.prompt(prompt()).block(TIMEOUT);
		assertThat(messages).containsExactly("green");
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	/**
	 * {@code tryReadFile} gives empty for a file it cannot read, but not for the interrupt the SDK
	 * uses to cancel a sync handler: that propagates, with the thread's interrupt flag still set,
	 * so a cancelled handler stops instead of carrying on as if the file were missing.
	 */
	@Test
	void tryReadFileDoesNotSwallowTheCancellingInterrupt() throws Exception {
		CountDownLatch reading = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<String> outcome = new AtomicReference<>();

		AcpSyncAgent agent = AcpAgent.sync(this.transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("s1", null, null))
			.promptHandler((request, context) -> {
				try {
					var content = context.tryReadFile("/slow");
					outcome.set("returned " + content + ", interrupted=" + Thread.currentThread().isInterrupted());
				}
				catch (RuntimeException e) {
					outcome.set(e.getClass().getSimpleName() + ", interrupted=" + Thread.currentThread().isInterrupted());
					throw e;
				}
				finally {
					done.countDown();
				}
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(this.transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(new ClientCapabilities(new AcpSchema.FileSystemCapability(true, false), null))
			.readTextFileHandler(req -> {
				reading.countDown();
				return Mono.never();
			})
			.build();

		agent.start();
		connect(client);

		Disposable turn = client.prompt(prompt()).subscribe(r -> {
		}, e -> {
		});
		assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
		turn.dispose();

		assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(outcome.get()).isEqualTo("CancellationException, interrupted=true");

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	/**
	 * {@code askPermission} asks about a tool call the client knows: it announces it with a
	 * {@code tool_call} update (kind {@code other}, not {@code edit} for every action) before the
	 * permission request names it, and settles it once the user answered.
	 */
	@Test
	void askPermissionAnnouncesTheToolCallItAsksAbout() {
		List<AcpSchema.SessionUpdate> updates = new CopyOnWriteArrayList<>();
		List<String> knownWhenAsked = new CopyOnWriteArrayList<>();
		AtomicReference<AcpSchema.ToolCallUpdate> asked = new AtomicReference<>();

		AcpAsyncAgent agent = AcpAgent.async(this.transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> Mono.just(InitializeResponse.ok()))
			.newSessionHandler(req -> Mono.just(new NewSessionResponse("s1", null, null)))
			.promptHandler((request, context) -> context.askPermission("Run the tests")
				.thenReturn(PromptResponse.endTurn()))
			.build();
		AcpAsyncClient client = AcpClient.async(this.transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(n -> Mono.fromRunnable(() -> updates.add(n.update())))
			.requestPermissionHandler(req -> {
				asked.set(req.toolCall());
				updates.stream()
					.filter(u -> u instanceof AcpSchema.ToolCall call && call.toolCallId().equals(req.toolCall().toolCallId()))
					.forEach(u -> knownWhenAsked.add(((AcpSchema.ToolCall) u).toolCallId()));
				return Mono.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected("allow")));
			})
			.build();

		agent.start().block(TIMEOUT);
		connect(client);
		client.prompt(prompt()).block(TIMEOUT);

		assertThat(asked.get()).isNotNull();
		assertThat(knownWhenAsked).as("tool call announced before the permission request")
			.containsExactly(asked.get().toolCallId());
		AcpSchema.ToolCall announced = (AcpSchema.ToolCall) updates.get(0);
		assertThat(announced.title()).isEqualTo("Run the tests");
		assertThat(announced.kind()).isEqualTo(AcpSchema.ToolKind.OTHER);
		assertThat(announced.status()).isEqualTo(AcpSchema.ToolCallStatus.PENDING);
		assertThat(asked.get().kind()).isNotEqualTo(AcpSchema.ToolKind.EDIT);
		assertThat(updates).last()
			.isInstanceOfSatisfying(AcpSchema.ToolCallUpdateNotification.class, done -> {
				assertThat(done.toolCallId()).isEqualTo(asked.get().toolCallId());
				assertThat(done.status()).isEqualTo(AcpSchema.ToolCallStatus.COMPLETED);
			});

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	/** {@code execute} carries the {@code truncated} flag of the client's terminal output. */
	@Test
	void executeCarriesTheTruncatedFlag() {
		AtomicReference<CommandResult> result = new AtomicReference<>();
		AcpSyncAgent agent = AcpAgent.sync(this.transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("s1", null, null))
			.promptHandler((request, context) -> {
				result.set(context.execute("make", "build"));
				return PromptResponse.endTurn();
			})
			.build();
		AcpAsyncClient client = AcpClient.async(this.transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(new ClientCapabilities(null, true))
			.createTerminalHandler(req -> Mono.just(new CreateTerminalResponse("term-t")))
			.waitForTerminalExitHandler(req -> Mono.just(new AcpSchema.WaitForTerminalExitResponse(0, null)))
			.terminalOutputHandler(req -> Mono.just(new AcpSchema.TerminalOutputResponse("...tail", true, null)))
			.releaseTerminalHandler(req -> Mono.just(new ReleaseTerminalResponse()))
			.build();
		agent.start();
		connect(client);
		client.prompt(prompt()).block(TIMEOUT);

		assertThat(result.get().output()).isEqualTo("...tail");
		assertThat(result.get().truncated()).isTrue();
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	private AcpAsyncClient terminalClient(CountDownLatch created, CountDownLatch released) {
		return AcpClient.async(this.transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(new ClientCapabilities(null, true))
			.createTerminalHandler(req -> {
				created.countDown();
				return Mono.just(new CreateTerminalResponse("term-1"));
			})
			.waitForTerminalExitHandler(req -> Mono.never())
			.terminalOutputHandler(req -> Mono.never())
			.releaseTerminalHandler(req -> {
				released.countDown();
				return Mono.just(new ReleaseTerminalResponse());
			})
			.build();
	}

}
