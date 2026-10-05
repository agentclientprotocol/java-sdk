/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client that stops reading the agent's standard output fills the pipe and blocks the
 * transport's writer. Messages that several threads send meanwhile (user threads, and the
 * inbound thread answering requests) must all be written, in each sender's order, once the
 * client reads again: none is lost, none fails.
 */
class StdioAgentBlockedOutputTest {

	private static final int SENDERS = 8;

	private static final int PER_SENDER = 200;

	private static final int REQUESTS = 200;

	private final AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();

	@Test
	void messagesSentWhileTheClientDoesNotReadAreAllWrittenOnceItDoes() throws Exception {
		Pipe toClient = Pipe.open();
		Pipe toAgent = Pipe.open();
		OutputStream agentOut = Channels.newOutputStream(toClient.sink());
		InputStream clientIn = Channels.newInputStream(toClient.source());
		InputStream agentIn = Channels.newInputStream(toAgent.source());
		OutputStream clientOut = Channels.newOutputStream(toAgent.sink());

		StdioAcpAgentTransport transport = new StdioAcpAgentTransport(jsonMapper, agentIn, agentOut);
		transport.start(request -> request.map(message -> {
			AcpSchema.JSONRPCRequest r = (AcpSchema.JSONRPCRequest) message;
			return (AcpSchema.JSONRPCMessage) new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, r.id(),
					Map.of("n", r.id()), null);
		})).block(Duration.ofSeconds(5));

		// Fill the pipe: the client is not reading, so the writer blocks in write().
		String filler = "x".repeat(16 * 1024);
		// On a separate thread: were the writer to write on the sending thread, this send would block.
		started(() -> {
			for (int i = 0; i < 16; i++) {
				transport.sendMessage(notification("filler", -1, i, filler)).block(Duration.ofSeconds(5));
			}
		});
		Thread.sleep(300);

		ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
		CountDownLatch go = new CountDownLatch(1);
		List<Thread> threads = new ArrayList<>();
		for (int s = 0; s < SENDERS; s++) {
			int sender = s;
			threads.add(started(() -> {
				try {
					go.await();
					for (int i = 0; i < PER_SENDER; i++) {
						transport.sendMessage(notification("session/update", sender, i, ""))
							.block(Duration.ofSeconds(5));
					}
				}
				catch (Throwable t) {
					failures.add(t);
				}
			}));
		}
		// Inbound requests, answered on the inbound thread while the users send.
		threads.add(started(() -> {
			try {
				go.await();
				for (int i = 0; i < REQUESTS; i++) {
					String line = jsonMapper.writeValueAsString(
							new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, i, "session/prompt", Map.of()))
							+ "\n";
					clientOut.write(line.getBytes(StandardCharsets.UTF_8));
					clientOut.flush();
				}
			}
			catch (Throwable t) {
				failures.add(t);
			}
		}));
		go.countDown();
		for (Thread t : threads) {
			t.join(30_000);
		}
		// The writer stays blocked well past the emitters' 100 ms busy loop.
		Thread.sleep(500);
		assertThat(failures).isEmpty();

		// The client reads again.
		BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));
		Map<Integer, Integer> next = new HashMap<>();
		int notifications = 0;
		boolean[] answered = new boolean[REQUESTS];
		int responses = 0;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while ((notifications < SENDERS * PER_SENDER || responses < REQUESTS) && System.nanoTime() < deadline) {
			String line = reader.readLine();
			AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, line);
			if (message instanceof AcpSchema.JSONRPCNotification n && n.method().equals("session/update")) {
				Map<?, ?> params = (Map<?, ?>) n.params();
				int sender = ((Number) params.get("sender")).intValue();
				int seq = ((Number) params.get("seq")).intValue();
				int expected = next.getOrDefault(sender, 0);
				assertThat(seq).as("sender %d order", sender).isEqualTo(expected);
				next.put(sender, expected + 1);
				notifications++;
			}
			else if (message instanceof AcpSchema.JSONRPCResponse r) {
				int id = ((Number) r.id()).intValue();
				assertThat(answered[id]).as("response %d written once", id).isFalse();
				answered[id] = true;
				responses++;
			}
		}
		assertThat(notifications).isEqualTo(SENDERS * PER_SENDER);
		assertThat(responses).isEqualTo(REQUESTS);
		transport.closeGracefully().block(Duration.ofSeconds(5));
	}

	private static Thread started(Runnable task) {
		Thread thread = new Thread(task);
		thread.start();
		return thread;
	}

	private static AcpSchema.JSONRPCNotification notification(String method, int sender, int seq, String pad) {
		return new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION, method,
				Map.of("sender", sender, "seq", seq, "pad", pad));
	}

}
