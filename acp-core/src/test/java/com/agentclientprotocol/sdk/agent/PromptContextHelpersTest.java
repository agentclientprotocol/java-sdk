/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
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
