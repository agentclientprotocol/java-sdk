/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.fixtures;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Small runnable ACP agent server for manually exercising the Streamable HTTP transport.
 *
 * @author Kaiser Dandangi
 */
public final class StreamableHttpAgentDemoServer {

	private static final Duration START_TIMEOUT = Duration.ofSeconds(30);

	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

	private static final String OPENAI_SYSTEM_PROMPT = """
			You are a small ACP demo agent running inside the Java SDK Streamable HTTP fixture.
			Answer concisely. If the user asks about implementation details, say that this
			fixture is exercising the ACP Streamable HTTP transport, not providing a full
			production agent runtime.
			""";

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
		PromptBackend promptBackend;
		try {
			promptBackend = options.backend().create();
		}
		catch (IllegalArgumentException e) {
			System.err.println(e.getMessage());
			System.exit(2);
			return;
		}

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
						: promptBackend.generate(normalized, request.sessionId(), cwd).flatMap(context::sendMessage);
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

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				server.closeGracefully().block(STOP_TIMEOUT);
			}
			finally {
				promptBackend.close();
			}
		}, "acp-demo-shutdown"));

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
				  --port <port>              Port to listen on. Defaults to 8080.
				  --path <path>              ACP endpoint path. Defaults to /acp.
				  --backend <backend>        Agent backend: echo or spring-ai-openai. Defaults to echo.
				  --openai-model <model>     OpenAI model for spring-ai-openai. Defaults to OPENAI_MODEL or gpt-4o-mini.
				  --strict                   Use strict transport routing.
				  --compatible               Use compatible transport routing. This is the default.
				  -h, --help                 Show this help.

				Environment:
				  OPENAI_API_KEY             Required when --backend spring-ai-openai is used.
				  OPENAI_MODEL               Optional default model for --backend spring-ai-openai.
				""");
	}

	private record Options(int port, String path, StreamableHttpAcpAgentTransport.RoutingMode routingMode,
			Backend backend, boolean help) {

		static Options parse(String[] args) {
			int port = 8080;
			String path = StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH;
			StreamableHttpAcpAgentTransport.RoutingMode routingMode =
					StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE;
			String backendName = "echo";
			String openAiModel = null;
			boolean help = false;

			for (int i = 0; i < args.length; i++) {
				String arg = args[i];
				switch (arg) {
					case "--port" -> port = parsePort(requireValue(args, ++i, "--port"));
					case "--path" -> path = requireValue(args, ++i, "--path");
					case "--backend" -> backendName = requireValue(args, ++i, "--backend");
					case "--openai-model" -> openAiModel = requireValue(args, ++i, "--openai-model");
					case "--strict" -> routingMode = StreamableHttpAcpAgentTransport.RoutingMode.STRICT;
					case "--compatible" -> routingMode = StreamableHttpAcpAgentTransport.RoutingMode.COMPATIBLE;
					case "-h", "--help" -> help = true;
					default -> throw new IllegalArgumentException("Unknown option: " + arg);
				}
			}

			if (!path.startsWith("/")) {
				throw new IllegalArgumentException("--path must start with /");
			}
			Backend backend = Backend.parse(backendName, openAiModel);
			return new Options(port, path, routingMode, backend, help);
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

	private sealed interface Backend permits EchoBackend, SpringAiOpenAiBackend {

		PromptBackend create();

		static Backend echo() {
			return new EchoBackend();
		}

		static Backend springAiOpenAi(String model) {
			return new SpringAiOpenAiBackend(model);
		}

		static Backend parse(String value, String openAiModel) {
			return switch (value) {
				case "echo" -> echo();
				case "spring-ai-openai" -> springAiOpenAi(openAiModel);
				default -> throw new IllegalArgumentException("Unknown backend: " + value);
			};
		}

	}

	@FunctionalInterface
	private interface PromptBackend {

		Mono<String> generate(String prompt, String sessionId, String cwd);

		default void close() {
		}

	}

	private record EchoBackend() implements Backend {

		@Override
		public PromptBackend create() {
			return new EchoPromptBackend();
		}

	}

	private static final class EchoPromptBackend implements PromptBackend {

		@Override
		public Mono<String> generate(String prompt, String sessionId, String cwd) {
			return Mono.just("Demo agent received: " + prompt + " [cwd=" + cwd + "]");
		}

	}

	private record SpringAiOpenAiBackend(String model) implements Backend {

		@Override
		public PromptBackend create() {
			String apiKey = System.getenv("OPENAI_API_KEY");
			if (apiKey == null || apiKey.isBlank()) {
				throw new IllegalArgumentException(
						"OPENAI_API_KEY is required when --backend spring-ai-openai is used");
			}

			OpenAiApi openAiApi = OpenAiApi.builder().apiKey(apiKey).build();
			OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
				.model(resolveOpenAiModel(this.model))
				.temperature(0.2)
				.maxTokens(800)
				.build();
			OpenAiChatModel chatModel = OpenAiChatModel.builder()
				.openAiApi(openAiApi)
				.defaultOptions(chatOptions)
				.build();

			return new SpringAiOpenAiPromptBackend(chatModel);
		}

	}

	private static final class SpringAiOpenAiPromptBackend implements PromptBackend {

		private final OpenAiChatModel chatModel;

		private final ExecutorService executorService;

		private final Scheduler scheduler;

		private SpringAiOpenAiPromptBackend(OpenAiChatModel chatModel) {
			this.chatModel = chatModel;
			AtomicInteger threadCounter = new AtomicInteger();
			this.executorService = Executors.newCachedThreadPool(task -> {
				Thread thread = new Thread(task, "acp-demo-openai-" + threadCounter.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			});
			this.scheduler = Schedulers.fromExecutorService(this.executorService, "acp-demo-openai");
		}

		@Override
		public Mono<String> generate(String prompt, String sessionId, String cwd) {
			return Mono.fromCallable(() -> generatePrompt(prompt, sessionId, cwd)).subscribeOn(this.scheduler);
		}

		@Override
		public void close() {
			this.scheduler.dispose();
			this.executorService.shutdownNow();
		}

		private String generatePrompt(String prompt, String sessionId, String cwd) {
			ChatResponse response = chatModel.call(new Prompt(List.of(new SystemMessage(OPENAI_SYSTEM_PROMPT),
					new UserMessage("Session: " + sessionId + "\nCWD: " + cwd + "\n\nUser prompt:\n" + prompt))));
			Generation generation = response.getResult();
			if (generation == null || generation.getOutput() == null || generation.getOutput().getText() == null
					|| generation.getOutput().getText().isBlank()) {
				return "(OpenAI returned an empty response)";
			}
			return generation.getOutput().getText();
		}

	}

	private static String resolveOpenAiModel(String model) {
		if (model != null && !model.isBlank()) {
			return model;
		}
		String envModel = System.getenv("OPENAI_MODEL");
		if (envModel != null && !envModel.isBlank()) {
			return envModel;
		}
		return "gpt-4o-mini";
	}

}
