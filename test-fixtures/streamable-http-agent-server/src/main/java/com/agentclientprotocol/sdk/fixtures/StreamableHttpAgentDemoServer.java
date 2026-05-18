/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.fixtures;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * Small runnable ACP agent server for manually exercising the Streamable HTTP transport.
 *
 * @author Kaiser Dandangi
 */
public final class StreamableHttpAgentDemoServer {

	private static final Duration START_TIMEOUT = Duration.ofSeconds(30);

	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

	private StreamableHttpAgentDemoServer() {
	}

	public static void main(String[] args) {
		Options options;
		try {
			options = Options.parse(args);
		}
		catch (IllegalArgumentException e) {
			System.err.println(e.getMessage());
			printUsage();
			System.exit(2);
			return;
		}

		if (options.help()) {
			printUsage();
			return;
		}

		Map<String, String> sessionCwds = new ConcurrentHashMap<>();
		AtomicInteger sessionCounter = new AtomicInteger();

		AcpAgentFactory agentFactory = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
			.requestTimeout(Duration.ofMinutes(2))
			.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok(
					new AcpSchema.AgentCapabilities(true, new AcpSchema.McpCapabilities(),
							new AcpSchema.PromptCapabilities()))))
			.newSessionHandler(request -> {
				String sessionId = "demo-session-" + sessionCounter.incrementAndGet();
				sessionCwds.put(sessionId, request.cwd());
				return Mono.just(new AcpSchema.NewSessionResponse(sessionId, null, null));
			})
			.loadSessionHandler(request -> {
				sessionCwds.put(request.sessionId(), request.cwd());
				return Mono.just(new AcpSchema.LoadSessionResponse(null, null));
			})
			.promptHandler((request, context) -> {
				String text = request.text();
				String normalized = text == null || text.isBlank() ? "(empty prompt)" : text;
				String cwd = sessionCwds.getOrDefault(request.sessionId(), "(unknown cwd)");
				Mono<Void> response = normalized.toLowerCase(Locale.ROOT).contains("permission")
						? context.askPermission("Demo agent permission check for session " + request.sessionId())
							.flatMap(allowed -> context.sendMessage(
									"Permission " + (allowed ? "granted" : "denied") + ". Prompt: " + normalized))
							.onErrorResume(error -> context.sendMessage(
									"Permission request failed in demo server: " + error.getMessage()))
						: context.sendMessage("Demo agent received: " + normalized + " [cwd=" + cwd + "]");
				return response.thenReturn(AcpSchema.PromptResponse.endTurn());
			})
			.cancelHandler(notification -> {
				System.out.println("Received cancel for session " + notification.sessionId());
				return Mono.empty();
			})
			.build());

		StreamableHttpAcpAgentTransport server = new StreamableHttpAcpAgentTransport(options.port(), options.path(),
				AcpJsonMapper.createDefault(), agentFactory)
			.routingMode(options.routingMode());

		Runtime.getRuntime().addShutdownHook(new Thread(() -> server.closeGracefully().block(STOP_TIMEOUT),
				"acp-demo-shutdown"));

		server.start().block(START_TIMEOUT);
		System.out.println("ACP Streamable HTTP demo agent listening at http://127.0.0.1:" + server.getPort()
				+ options.path());
		System.out.println("Press Ctrl-C to stop.");
		server.awaitTermination().block();
	}

	private static void printUsage() {
		System.out.println("""
				Usage: java -jar acp-streamable-http-agent-server.jar [options]

				Options:
				  --port <port>       Port to listen on. Defaults to 8080.
				  --path <path>       ACP endpoint path. Defaults to /acp.
				  --strict            Use strict transport routing.
				  --compatible        Use compatible transport routing. This is the default.
				  -h, --help          Show this help.
				""");
	}

	private record Options(int port, String path, StreamableHttpAcpAgentTransport.RoutingMode routingMode,
			boolean help) {

		static Options parse(String[] args) {
			int port = 8080;
			String path = StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH;
			StreamableHttpAcpAgentTransport.RoutingMode routingMode =
					StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE;
			boolean help = false;

			for (int i = 0; i < args.length; i++) {
				String arg = args[i];
				switch (arg) {
					case "--port" -> port = parsePort(requireValue(args, ++i, "--port"));
					case "--path" -> path = requireValue(args, ++i, "--path");
					case "--strict" -> routingMode = StreamableHttpAcpAgentTransport.RoutingMode.STRICT;
					case "--compatible" -> routingMode = StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE;
					case "-h", "--help" -> help = true;
					default -> throw new IllegalArgumentException("Unknown option: " + arg);
				}
			}

			if (!path.startsWith("/")) {
				throw new IllegalArgumentException("--path must start with /");
			}
			return new Options(port, path, routingMode, help);
		}

		private static String requireValue(String[] args, int index, String option) {
			if (index >= args.length || args[index].startsWith("--")) {
				throw new IllegalArgumentException(option + " requires a value");
			}
			return args[index];
		}

		private static int parsePort(String value) {
			try {
				int port = Integer.parseInt(value);
				if (port <= 0) {
					throw new IllegalArgumentException("--port must be positive");
				}
				return port;
			}
			catch (NumberFormatException e) {
				throw new IllegalArgumentException("--port must be a number", e);
			}
		}

	}

}
