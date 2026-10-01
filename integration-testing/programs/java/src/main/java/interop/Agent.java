package interop;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import reactor.core.publisher.Mono;

/**
 * The Java interop agent of the step catalogue (integration-testing/steps.json), driven by
 * directives in the prompt text. One binary serves every scenario.
 *
 * <pre>
 *   launch/agent.sh --transport stdio                 ACP on stdin/stdout; exits when stdin ends
 *   launch/agent.sh --transport http|ws --port &lt;p&gt;    listens on /acp (HTTP and WebSocket upgrade on
 *                                                     the same endpoint); prints READY &lt;port&gt; on stdout
 * </pre>
 *
 * Every diagnostic, and every {@code STEP agent.<id>} line, goes to stderr: on stdio, stdout is the
 * protocol stream. This is the WP0 seed: it implements the directives of the seeded steps (plain
 * text, {@code #permission allow}, {@code #fs write}) and answers any other directive with a
 * -32602 "unknown directive" error.
 */
public class Agent {

	static final AtomicInteger sessions = new AtomicInteger();

	public static void main(String[] args) throws Exception {
		String transport = null;
		int port = 0;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--transport" -> transport = args[++i];
				case "--port" -> port = Integer.parseInt(args[++i]);
				default -> usage("unknown argument " + args[i]);
			}
		}
		if (transport == null) {
			usage("--transport is required");
		}
		switch (transport) {
			case "stdio" -> runStdio();
			case "http", "ws" -> runHttp(port);
			default -> usage("unknown transport " + transport);
		}
	}

	static void usage(String problem) {
		System.err.println("agent: " + problem);
		System.err.println("usage: agent --transport stdio | --transport http|ws --port <port>");
		System.exit(2);
	}

	static void runStdio() {
		AcpAsyncAgent agent = build(new StdioAcpAgentTransport(AcpJsonMapper.createDefault()));
		agent.start().block();
		log("stdio agent started");
		agent.awaitTermination().block();
		log("stdin closed; exiting");
		System.exit(0);
	}

	static void runHttp(int port) throws Exception {
		// One agent per connection; the WebSocket upgrade is served on the same endpoint.
		AcpAgentFactory factory = AcpAgentFactory.async(Agent::build);
		StreamableHttpAcpAgentTransport server = new StreamableHttpAcpAgentTransport(port,
				StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH, AcpJsonMapper.createDefault(), factory);
		server.start().block();
		attachRequestLog(server);
		log("listening http://127.0.0.1:" + server.getPort() + "/acp (HTTP and WebSocket)");
		System.out.println("READY " + server.getPort());
		System.out.flush();
		server.awaitTermination().block();
	}

	/** Builds one agent for one connection. */
	static AcpAsyncAgent build(AcpAgentTransport transport) {
		AtomicReference<AcpSchema.InitializeRequest> init = new AtomicReference<>();
		return AcpAgent.async(transport).initializeHandler(r -> {
			init.set(r);
			log("[agent] initialize " + r);
			return Mono.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(true, null, null),
					List.of(new AcpSchema.AuthMethod("interop-auth", "Interop auth", "Accepts any authenticate call")),
					new AcpSchema.Implementation("interop-java-agent", "1"), null));
		}).newSessionHandler(r -> {
			String id = "java-sess-" + sessions.incrementAndGet();
			log("[agent] session/new " + id);
			return Mono.just(new AcpSchema.NewSessionResponse(id, null, null));
		}).loadSessionHandler(r -> {
			log("[agent] session/load " + r.sessionId());
			return Mono.just(new AcpSchema.LoadSessionResponse(null, null));
		}).promptHandler((request, context) -> prompt(request, context, init.get())).build();
	}

	static Mono<AcpSchema.PromptResponse> prompt(AcpSchema.PromptRequest request, PromptContext context,
			AcpSchema.InitializeRequest init) {
		String text = request.text();
		log("[agent] session/prompt " + abbreviate(text));
		if (!text.startsWith("#")) {
			return context.sendMessage("echo: ")
				.then(context.sendMessage(text))
				.thenReturn(AcpSchema.PromptResponse.endTurn());
		}
		String[] words = text.split(" ", 3);
		String directive = words[0] + (words.length > 1 ? " " + words[1] : "");
		return switch (directive) {
			case "#permission allow" -> permissionAllow(request.sessionId(), context);
			case "#fs write" -> fsWrite(request.sessionId(), context, init, words.length > 2 ? words[2] : "");
			default -> Mono.error(new AcpProtocolException(-32602, "unknown directive: " + words[0]));
		};
	}

	static Mono<AcpSchema.PromptResponse> permissionAllow(String sessionId, PromptContext context) {
		long t0 = System.nanoTime();
		AcpSchema.RequestPermissionRequest req = new AcpSchema.RequestPermissionRequest(sessionId,
				new AcpSchema.ToolCallUpdate("perm-1", "interop permission", AcpSchema.ToolKind.EDIT,
						AcpSchema.ToolCallStatus.PENDING, null, null, null, null),
				List.of(new AcpSchema.PermissionOption("allow", "Allow", AcpSchema.PermissionOptionKind.ALLOW_ONCE),
						new AcpSchema.PermissionOption("reject", "Reject", AcpSchema.PermissionOptionKind.REJECT_ONCE)));
		return context.requestPermission(req).flatMap(r -> {
			String said = r.outcome() instanceof AcpSchema.PermissionSelected s ? "selected " + s.optionId()
					: "cancelled";
			step("perm.selected", said.equals("selected allow"), t0, "outcome " + said);
			return context.sendMessage("permission: " + said);
		}).thenReturn(AcpSchema.PromptResponse.endTurn());
	}

	static Mono<AcpSchema.PromptResponse> fsWrite(String sessionId, PromptContext context,
			AcpSchema.InitializeRequest init, String args) {
		long t0 = System.nanoTime();
		int space = args.indexOf(' ');
		String path = space < 0 ? args : args.substring(0, space);
		String content = space < 0 ? "" : args.substring(space + 1);
		AcpSchema.ClientCapabilities caps = init == null ? null : init.clientCapabilities();
		if (caps == null || caps.fs() == null || !Boolean.TRUE.equals(caps.fs().writeTextFile())) {
			step("fs.write", false, t0, "the client did not advertise fs.writeTextFile");
			return context.sendMessage("fs write error capability").thenReturn(AcpSchema.PromptResponse.endTurn());
		}
		return context.writeTextFile(new AcpSchema.WriteTextFileRequest(sessionId, path, content))
			.then(Mono.fromCallable(() -> {
				step("fs.write", true, t0, "fs/write_text_file answered without error");
				return "fs write ok";
			}))
			.onErrorResume(e -> {
				step("fs.write", false, t0, "fs/write_text_file failed: " + e);
				return Mono.just("fs write error " + code(e));
			})
			.flatMap(context::sendMessage)
			.thenReturn(AcpSchema.PromptResponse.endTurn());
	}

	static String code(Throwable e) {
		return e instanceof AcpProtocolException p ? Integer.toString(p.getCode()) : e.getClass().getSimpleName();
	}

	/** An agent-side assertion, {@code STEP agent.<id> PASS|FAIL (<ms> ms) -> <detail>}, on stderr. */
	static void step(String id, boolean pass, long t0, String detail) {
		System.err.println("STEP agent." + id + (pass ? " PASS" : " FAIL") + " (" + (System.nanoTime() - t0) / 1_000_000
				+ " ms) -> " + detail.replace('\n', ' '));
		System.err.flush();
	}

	static void log(String line) {
		System.err.println(line);
	}

	static String abbreviate(String s) {
		return s.length() > 200 ? s.substring(0, 200) + "... (" + s.length() + " chars)" : s;
	}

	/**
	 * Harness-only instrumentation: the transport has no public request-log hook, so reach its
	 * Jetty server reflectively. If the field is renamed the scenario fails on its missing
	 * {@code [http]} lines rather than silently passing.
	 */
	static void attachRequestLog(StreamableHttpAcpAgentTransport transport) throws Exception {
		Field f = StreamableHttpAcpAgentTransport.class.getDeclaredField("server");
		f.setAccessible(true);
		Server jetty = (Server) f.get(transport);
		jetty.setRequestLog(new CustomRequestLog((RequestLog.Writer) line -> System.err.println(line),
				"[http] %m %U %H -> %s ct=\"%{Content-Type}i\" accept=\"%{Accept}i\" conn=\"%{Acp-Connection-Id}i\""
						+ " sess=\"%{Acp-Session-Id}i\" upgrade=\"%{Upgrade}i\" respct=\"%{Content-Type}o\""));
	}

}
