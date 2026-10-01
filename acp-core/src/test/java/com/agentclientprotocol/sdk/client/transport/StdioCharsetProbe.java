/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * Child-process side of {@link StdioAcpClientTransportCharsetTest}. With {@code agent} it
 * is a minimal agent that writes one UTF-8 notification carrying non-ASCII text; otherwise
 * it is a client that runs {@link StdioAcpClientTransport} against that agent and prints,
 * in ASCII, its default charset and the text it received.
 */
public final class StdioCharsetProbe {

	static final String TEXT = "café ✓";

	static final String PREFIX = "PROBE ";

	private StdioCharsetProbe() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && "agent".equals(args[0])) {
			runAgent();
			return;
		}
		runClient();
	}

	private static void runAgent() throws Exception {
		String line = "{\"jsonrpc\":\"2.0\",\"method\":\"probe\",\"params\":{\"text\":\"" + TEXT + "\"}}\n";
		System.out.write(line.getBytes(StandardCharsets.UTF_8));
		System.out.flush();
		// Stay up until the client closes stdin.
		InputStream in = System.in;
		while (in.read() != -1) {
			// discard
		}
	}

	private static void runClient() throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		StdioAcpClientTransport transport = new StdioAcpClientTransport(AgentParameters.builder(java)
			.arg("-cp")
			.arg(System.getProperty("java.class.path"))
			.arg(StdioCharsetProbe.class.getName())
			.arg("agent")
			.build());
		CompletableFuture<String> received = new CompletableFuture<>();
		transport.connect(message -> message.doOnNext(m -> {
			if (m instanceof AcpSchema.JSONRPCNotification notification
					&& notification.params() instanceof Map<?, ?> params) {
				received.complete(String.valueOf(params.get("text")));
			}
		}).then(Mono.empty())).block(Duration.ofSeconds(30));
		String text = received.get(30, TimeUnit.SECONDS);
		System.out.println(PREFIX + "charset=" + Charset.defaultCharset().name());
		System.out.println(PREFIX + "text=" + escape(text));
		System.out.flush();
		transport.closeGracefully().block(Duration.ofSeconds(10));
		System.exit(0);
	}

	static String escape(String text) {
		StringBuilder escaped = new StringBuilder();
		text.chars().forEach(c -> escaped.append(c < 128 ? String.valueOf((char) c) : String.format("\\u%04x", c)));
		return escaped.toString();
	}

}
