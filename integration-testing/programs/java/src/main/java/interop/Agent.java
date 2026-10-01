package interop;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.PromptTimeouts;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import reactor.core.publisher.Flux;
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
 * protocol stream.
 *
 * <p>
 * Sessions live in one table for the whole process, so a session created on one HTTP connection
 * can be loaded on another ({@code http.reconnect}). The prompt timeouts are the SDK's
 * {@link PromptTimeouts}: the cancel grace period is 1 s ({@code cancel.grace}; env
 * {@code INTEROP_CANCEL_GRACE_MS} overrides it, the SDK default is 60 s) and a prompt may run at
 * most 60 s, so a {@code #hang} nobody cancels still ends.
 * </p>
 *
 * <p>
 * What the Java SDK cannot do yet is answered honestly, never faked: no {@code configOptions} on session responses (P1b), no {@code $/cancel_request}
 * (P2), no tool call {@code name} (P3), no terminal auth method or {@code auth.logout} capability
 * (P5, P7), no view of {@code session.configOptions.boolean} in the client capabilities (P6), no
 * {@code _meta} on permission requests (P8), and no generic send or receive of extension methods
 * (P9). The unknown update is an {@code AcpSchema.UnknownSessionUpdate}, which writes the type and
 * fields it is given.
 * </p>
 */
public class Agent {

	static final AcpJsonMapper JSON = AcpJsonMapper.createDefault();

	static final AtomicInteger sessionCounter = new AtomicInteger();

	/** Every session this process created, across connections. */
	static final Map<String, SessionState> SESSIONS = new ConcurrentHashMap<>();

	static final Duration CANCEL_GRACE = Duration.ofMillis(Long.parseLong(env("INTEROP_CANCEL_GRACE_MS", "1000")));

	static final Duration MAX_PROMPT = Duration.ofSeconds(60);

	static final String FS_READ_CONTENT = "line1\nline2\nline3\n";

	public static void main(String[] args) throws Exception {
		String transport = null;
		int port = 0;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--transport" -> transport = value(args, ++i);
				case "--port" -> port = Integer.parseInt(value(args, ++i));
				default -> usage("unknown argument " + args[i]);
			}
		}
		if (transport == null) {
			usage("--transport is required");
		}
		log("prompt timeouts: " + new PromptTimeouts(CANCEL_GRACE, MAX_PROMPT) + " (SDK defaults "
				+ PromptTimeouts.DEFAULTS + ")");
		switch (transport) {
			case "stdio" -> runStdio();
			case "http", "ws" -> runHttp(port);
			default -> usage("unknown transport " + transport);
		}
	}

	static String value(String[] args, int i) {
		if (i >= args.length) {
			usage("missing value for " + args[i - 1]);
		}
		return args[i];
	}

	static void usage(String problem) {
		System.err.println("agent: " + problem);
		System.err.println("usage: agent --transport stdio | --transport http|ws --port <port>");
		System.exit(2);
	}

	static void runStdio() {
		AcpAsyncAgent agent = build(new StdioAcpAgentTransport(JSON));
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
				StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH, JSON, factory);
		server.start().block();
		attachRequestLog(server);
		log("listening http://127.0.0.1:" + server.getPort() + "/acp (HTTP and WebSocket)");
		System.out.println("READY " + server.getPort());
		System.out.flush();
		server.awaitTermination().block();
	}

	// ---------------------------------------------------------------- fixtures

	static AcpSchema.SessionModeState modes(String current) {
		return new AcpSchema.SessionModeState(current, List.of(new AcpSchema.SessionMode("interop-mode-a", "Mode A", null),
				new AcpSchema.SessionMode("interop-mode-b", "Mode B", null)));
	}

	static List<AcpSchema.SessionConfigOption> configOptions(String model) {
		// verbose (boolean) is offered only to a client that advertised
		// session.configOptions.boolean, which ClientCapabilities cannot carry yet (P6).
		return List.of(new AcpSchema.SessionConfigSelect("model", "Model", model,
				List.of(new AcpSchema.SessionConfigSelectOption("model-a", "Model A"),
						new AcpSchema.SessionConfigSelectOption("model-b", "Model B"))));
	}

	/** The state of one session, shared by every connection of this process. */
	static final class SessionState {

		final String id;

		final String cwd;

		/** Replayed on session/load: per plain-text turn, the user chunk and the agent chunks. */
		final List<AcpSchema.SessionUpdate> history = new CopyOnWriteArrayList<>();

		volatile boolean closed;

		volatile String mode = "interop-mode-a";

		volatile String model = "model-a";

		/** The running turn, or null. */
		final AtomicReference<Turn> turn = new AtomicReference<>();

		SessionState(String id, String cwd) {
			this.id = id;
			this.cwd = cwd;
		}

	}

	/** One running prompt, as the handler sees it: when (and why) it was asked to stop. */
	static final class Turn {

		volatile long cancelledAtNanos;

		volatile boolean closed;

		boolean cancelled() {
			return this.cancelledAtNanos != 0;
		}

		void cancel(boolean byClose) {
			if (byClose) {
				this.closed = true;
			}
			if (this.cancelledAtNanos == 0) {
				this.cancelledAtNanos = System.nanoTime();
			}
		}

	}

	// ---------------------------------------------------------------- the agent

	/** Builds one agent for one connection. */
	static AcpAsyncAgent build(AcpAgentTransport transport) {
		AtomicReference<AcpSchema.InitializeRequest> init = new AtomicReference<>();
		AtomicReference<AcpAsyncAgent> self = new AtomicReference<>();
		AcpAsyncAgent agent = AcpAgent.async(transport)
			.cancelGracePeriod(CANCEL_GRACE)
			.maxPromptDuration(MAX_PROMPT)
			.initializeHandler(r -> {
				init.set(r);
				log("[agent] initialize " + r);
				// No auth.logout (P7) and no terminal auth method (P5): the records cannot carry them.
				return Mono.just(new AcpSchema.InitializeResponse(1,
						new AcpSchema.AgentCapabilities(true,
								new AcpSchema.SessionCapabilities(Map.of(), Map.of(), Map.of(), Map.of(), null, null),
								new AcpSchema.McpCapabilities(false, false), new AcpSchema.PromptCapabilities(false, false, false),
								null),
						List.of(new AcpSchema.AuthMethod("interop-auth", "Interop auth", "Accepts any authenticate call")),
						new AcpSchema.Implementation("interop-java-agent", "1"), null));
			})
			.authenticateHandler(r -> {
				long t0 = System.nanoTime();
				boolean ok = "interop-auth".equals(r.methodId());
				step("auth.authenticate", ok, t0, "methodId " + r.methodId());
				return ok ? Mono.just(new AcpSchema.AuthenticateResponse())
						: Mono.error(new AcpProtocolException(-32602, "unknown auth method " + r.methodId()));
			})
			.logoutHandler(r -> Mono.just(new AcpSchema.LogoutResponse()))
			.newSessionHandler(r -> {
				String id = "java-sess-" + sessionCounter.incrementAndGet();
				SESSIONS.put(id, new SessionState(id, r.cwd()));
				log("[agent] session/new " + id + " cwd " + r.cwd());
				return Mono.just(new AcpSchema.NewSessionResponse(id, modes("interop-mode-a"), configOptions("model-a")));
			})
			.loadSessionHandler(r -> known(r.sessionId()).flatMap(s -> {
				log("[agent] session/load " + s.id + ": replaying " + s.history.size() + " updates");
				return Flux.fromIterable(s.history)
					.concatMap(u -> self.get().sendSessionUpdate(s.id, u))
					.then(Mono.just(new AcpSchema.LoadSessionResponse(modes(s.mode), configOptions(s.model))));
			}))
			.resumeSessionHandler(r -> known(r.sessionId()).map(s -> {
				log("[agent] session/resume " + s.id);
				return new AcpSchema.ResumeSessionResponse(modes(s.mode), configOptions(s.model));
			}))
			.listSessionsHandler(r -> {
				List<AcpSchema.SessionInfo> out = new ArrayList<>();
				for (SessionState s : SESSIONS.values()) {
					if (!s.closed && (r.cwd() == null || r.cwd().equals(s.cwd))) {
						out.add(new AcpSchema.SessionInfo(s.id, s.cwd));
					}
				}
				return Mono.just(new AcpSchema.ListSessionsResponse(out));
			})
			.closeSessionHandler(r -> {
				long t0 = System.nanoTime();
				SessionState s = SESSIONS.get(r.sessionId());
				if (s == null) {
					step("session.close", false, t0, "close named an unknown session " + r.sessionId());
					return Mono.error(new AcpProtocolException(-32602, "unknown session " + r.sessionId()));
				}
				s.closed = true;
				Turn turn = s.turn.get();
				if (turn != null) {
					turn.cancel(true);
				}
				step("session.close", turn != null, t0,
						turn != null ? "closed " + s.id + "; its running turn was cancelled" : "closed " + s.id + " with no running turn");
				return Mono.just(new AcpSchema.CloseSessionResponse());
			})
			.deleteSessionHandler(r -> {
				SESSIONS.remove(r.sessionId());
				return Mono.just(new AcpSchema.DeleteSessionResponse());
			})
			.forkSessionHandler(r -> known(r.sessionId()).map(s -> {
				String id = "java-sess-" + sessionCounter.incrementAndGet();
				SessionState fork = new SessionState(id, r.cwd());
				fork.history.addAll(s.history);
				SESSIONS.put(id, fork);
				return new AcpSchema.ForkSessionResponse(id, modes(s.mode));
			}))
			.setSessionModeHandler(r -> known(r.sessionId()).flatMap(s -> {
				long t0 = System.nanoTime();
				boolean knownMode = r.modeId().equals("interop-mode-a") || r.modeId().equals("interop-mode-b");
				step("mode.set", knownMode && r.modeId().equals("interop-mode-b"), t0, "set_mode " + r.modeId());
				if (!knownMode) {
					return Mono.error(new AcpProtocolException(-32602, "unknown mode " + r.modeId()));
				}
				s.mode = r.modeId();
				return Mono.just(new AcpSchema.SetSessionModeResponse());
			}))
			.setSessionConfigOptionHandler(r -> known(r.sessionId()).flatMap(s -> {
				if ("model".equals(r.configId()) && ("model-a".equals(r.value()) || "model-b".equals(r.value()))) {
					s.model = (String) r.value();
					return Mono.just(new AcpSchema.SetSessionConfigOptionResponse(configOptions(s.model)));
				}
				return Mono.error(new AcpProtocolException(-32602,
						"unknown config option " + r.configId() + "=" + r.value()
								+ " (verbose needs session.configOptions.boolean, which the Java SDK cannot see: P6)"));
			}))
			.cancelHandler(n -> {
				SessionState s = SESSIONS.get(n.sessionId());
				Turn turn = s == null ? null : s.turn.get();
				log("[agent] session/cancel " + n.sessionId() + (turn == null ? " (no running turn)" : ""));
				if (turn != null) {
					turn.cancel(false);
				}
				return Mono.empty();
			})
			.promptHandler((request, context) -> prompt(request, context, init.get()))
			.build();
		self.set(agent);
		return agent;
	}

	static Mono<SessionState> known(String sessionId) {
		SessionState s = sessionId == null ? null : SESSIONS.get(sessionId);
		if (s == null) {
			return Mono.error(new AcpProtocolException(-32602, "unknown session " + sessionId));
		}
		if (s.closed) {
			return Mono.error(new AcpProtocolException(-32602, "session " + sessionId + " is closed"));
		}
		return Mono.just(s);
	}

	static Mono<AcpSchema.PromptResponse> prompt(AcpSchema.PromptRequest request, PromptContext context,
			AcpSchema.InitializeRequest init) {
		String text = request.text();
		log("[agent] session/prompt " + request.sessionId() + ": " + abbreviate(text));
		return known(request.sessionId()).flatMap(s -> {
			Turn turn = new Turn();
			s.turn.set(turn);
			return directive(s, turn, request, context, init).doFinally(sig -> s.turn.compareAndSet(turn, null));
		});
	}

	static Mono<AcpSchema.PromptResponse> directive(SessionState s, Turn turn, AcpSchema.PromptRequest request,
			PromptContext context, AcpSchema.InitializeRequest init) {
		String text = request.text();
		if (!text.startsWith("#")) {
			s.history.add(new AcpSchema.UserMessageChunk("user_message_chunk", new AcpSchema.TextContent(text)));
			s.history.add(chunk("echo: "));
			s.history.add(chunk(text));
			return context.sendMessage("echo: ").then(context.sendMessage(text)).thenReturn(endTurn());
		}
		String[] words = text.split(" ");
		String name = words[0];
		String sub = words.length > 1 ? words[1] : "";
		AcpSchema.ClientCapabilities caps = init == null ? null : init.clientCapabilities();
		return switch (name) {
			case "#permission" -> switch (sub) {
				case "allow" -> permission(s.id, context, false);
				case "hold" -> permission(s.id, context, true);
				default -> unknown(text);
			};
			case "#fs" -> switch (sub) {
				case "write" -> fsWrite(s.id, context, caps, rest(text, 2));
				case "read" -> fsRead(s.id, context, caps, words);
				case "read-slow" -> {
					// The catalogue's agent sends $/cancel_request for its own fs read; the Java SDK
					// has no $/cancel_request (P2), so the read is not even started.
					step("cancel-request.agent", false, System.nanoTime(), "P2: the Java agent cannot send $/cancel_request");
					yield context.sendMessage("cancel-request unsupported (P2)").thenReturn(endTurn());
				}
				default -> unknown(text);
			};
			case "#emit" -> emit(context, words);
			case "#stop" -> stop(context, sub);
			case "#slow" -> slow(context, turn, words);
			case "#hang" -> Mono.never();
			case "#terminal" -> switch (sub) {
				case "run" -> terminalRun(s.id, context, caps, words);
				case "kill" -> terminalKill(s.id, context, caps, words);
				default -> unknown(text);
			};
			case "#elicit" -> switch (sub) {
				case "form" -> elicitForm(s.id, context, caps);
				case "url" -> elicitUrl(s.id, context, caps);
				default -> unknown(text);
			};
			case "#ext" -> switch (sub) {
				case "request" -> {
					step("ext.agent-request", false, System.nanoTime(),
							"P9: the Java agent has no generic request send for " + (words.length > 2 ? words[2] : "?"));
					yield context.sendMessage("ext: unsupported (P9)").thenReturn(endTurn());
				}
				case "notify" -> context.sendMessage("ext notify unsupported (P9)").thenReturn(endTurn());
				// Extension notifications cannot be registered on the Java agent (P9): none is ever recorded.
				case "last-notification" -> context.sendMessage("ext last: none").thenReturn(endTurn());
				default -> unknown(text);
			};
			case "#meta" -> meta(context, request);
			case "#echo-caps" -> echoCaps(context, init);
			case "#len" -> context.sendMessage("len=" + rest(text, 1).length()).thenReturn(endTurn());
			case "#big" -> big(context, sub);
			default -> unknown(text);
		};
	}

	static Mono<AcpSchema.PromptResponse> unknown(String text) {
		return Mono.error(new AcpProtocolException(-32602, "unknown directive: " + text.split(" ")[0]));
	}

	static AcpSchema.PromptResponse endTurn() {
		return AcpSchema.PromptResponse.endTurn();
	}

	static AcpSchema.PromptResponse cancelled() {
		return new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED);
	}

	static AcpSchema.AgentMessageChunk chunk(String text) {
		return new AcpSchema.AgentMessageChunk("agent_message_chunk", new AcpSchema.TextContent(text));
	}

	/** Everything after the first {@code n} space-separated words. */
	static String rest(String text, int n) {
		int at = 0;
		for (int i = 0; i < n; i++) {
			at = text.indexOf(' ', at);
			if (at < 0) {
				return "";
			}
			at++;
		}
		return text.substring(at);
	}

	// ---------------------------------------------------------------- directives

	static Mono<AcpSchema.PromptResponse> permission(String sessionId, PromptContext context, boolean hold) {
		long t0 = System.nanoTime();
		String id = hold ? "perm.cancelled" : "perm.selected";
		AcpSchema.RequestPermissionRequest req = new AcpSchema.RequestPermissionRequest(sessionId,
				new AcpSchema.ToolCallUpdate("perm-1", "interop permission", AcpSchema.ToolKind.EDIT,
						AcpSchema.ToolCallStatus.PENDING, null, null, null, null),
				List.of(new AcpSchema.PermissionOption("allow", "Allow", AcpSchema.PermissionOptionKind.ALLOW_ONCE),
						new AcpSchema.PermissionOption("reject", "Reject", AcpSchema.PermissionOptionKind.REJECT_ONCE)));
		return context.requestPermission(req).flatMap(r -> {
			String said = r.outcome() instanceof AcpSchema.PermissionSelected sel ? "selected " + sel.optionId()
					: "cancelled";
			step(id, said.equals(hold ? "cancelled" : "selected allow"), t0, "outcome " + said);
			return context.sendMessage("permission: " + said)
				.thenReturn(said.equals("cancelled") ? cancelled() : endTurn());
		}).onErrorResume(e -> {
			step(id, false, t0, "session/request_permission failed: " + e);
			return Mono.error(e);
		});
	}

	static boolean fsAllowed(AcpSchema.ClientCapabilities caps, boolean write) {
		if (caps == null || caps.fs() == null) {
			return false;
		}
		return Boolean.TRUE.equals(write ? caps.fs().writeTextFile() : caps.fs().readTextFile());
	}

	static Mono<AcpSchema.PromptResponse> fsWrite(String sessionId, PromptContext context,
			AcpSchema.ClientCapabilities caps, String args) {
		long t0 = System.nanoTime();
		int space = args.indexOf(' ');
		String path = space < 0 ? args : args.substring(0, space);
		String content = space < 0 ? "" : args.substring(space + 1);
		if (!fsAllowed(caps, true)) {
			step("fs.write", false, t0, "the client did not advertise fs.writeTextFile");
			return context.sendMessage("fs write error capability").thenReturn(endTurn());
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
			.thenReturn(endTurn());
	}

	/** {@code #fs read <path> [line=<n>] [limit=<n>]}: fs.read, fs.read-range or fs.read-missing. */
	static Mono<AcpSchema.PromptResponse> fsRead(String sessionId, PromptContext context,
			AcpSchema.ClientCapabilities caps, String[] words) {
		long t0 = System.nanoTime();
		String path = words.length > 2 ? words[2] : "";
		Integer line = null;
		Integer limit = null;
		for (int i = 3; i < words.length; i++) {
			if (words[i].startsWith("line=")) {
				line = Integer.valueOf(words[i].substring(5));
			}
			else if (words[i].startsWith("limit=")) {
				limit = Integer.valueOf(words[i].substring(6));
			}
		}
		String id = line != null || limit != null ? "fs.read-range" : path.endsWith("/no-such-file.txt") ? "fs.read-missing"
				: "fs.read";
		if (!fsAllowed(caps, false)) {
			step(id, false, t0, "the client did not advertise fs.readTextFile");
			return context.sendMessage("fs read error capability").thenReturn(endTurn());
		}
		return context.readTextFile(new AcpSchema.ReadTextFileRequest(sessionId, path, line, limit)).map(r -> {
			String content = r.content();
			switch (id) {
				case "fs.read" -> step(id, FS_READ_CONTENT.equals(content), t0, "content " + quote(content));
				case "fs.read-range" -> step(id, content != null && content.trim().equals("line2"), t0,
						"content " + quote(content));
				default -> step(id, false, t0, "reading a missing file succeeded: " + quote(content));
			}
			return content == null ? "" : content;
		}).onErrorResume(e -> {
			int code = codeOf(e);
			step(id, id.equals("fs.read-missing") && code != 0, t0, "fs/read_text_file failed: " + e);
			return Mono.just("fs read error " + (code != 0 ? code : e.getClass().getSimpleName()));
		}).flatMap(context::sendMessage).thenReturn(endTurn());
	}

	static Mono<AcpSchema.PromptResponse> emit(PromptContext context, String[] words) {
		String kind = words.length > 1 ? words[1] : "";
		String sid = context.getSessionId();
		AcpSchema.SessionUpdate toolCall = new AcpSchema.ToolCall("tool_call", "call-1", "interop tool",
				AcpSchema.ToolKind.READ, AcpSchema.ToolCallStatus.PENDING, null, null, null, null, null);
		List<AcpSchema.SessionUpdate> updates = switch (kind) {
			case "user_message_chunk" -> List.of(new AcpSchema.UserMessageChunk("user_message_chunk",
					new AcpSchema.TextContent("user-chunk")));
			case "agent_thought_chunk" -> List.of(new AcpSchema.AgentThoughtChunk("agent_thought_chunk",
					new AcpSchema.TextContent("thinking")));
			// name= cannot be set: AcpSchema.ToolCall has no name (P3); the tool call goes out without it.
			case "tool_call" -> List.of(toolCall);
			case "tool_call_update" -> List.of(toolCall,
					new AcpSchema.ToolCallUpdateNotification("tool_call_update", "call-1", null, null,
							AcpSchema.ToolCallStatus.COMPLETED,
							List.of(new AcpSchema.ToolCallContentBlock("content", new AcpSchema.TextContent("tool output"))),
							null, null, null, null));
			case "plan" -> List.of(new AcpSchema.Plan("plan",
					List.of(new AcpSchema.PlanEntry("step one", AcpSchema.PlanEntryPriority.HIGH, AcpSchema.PlanEntryStatus.PENDING),
							new AcpSchema.PlanEntry("step two", AcpSchema.PlanEntryPriority.LOW,
									AcpSchema.PlanEntryStatus.COMPLETED))));
			case "available_commands_update" -> List.of(new AcpSchema.AvailableCommandsUpdate("available_commands_update",
					List.of(new AcpSchema.AvailableCommand("interop", "interop command",
							new AcpSchema.AvailableCommandInput("args")))));
			case "current_mode_update" -> List.of(new AcpSchema.CurrentModeUpdate("current_mode_update", "interop-mode-b"));
			case "config_option_update" -> List.of(new AcpSchema.ConfigOptionUpdate("config_option_update",
					configOptions("model-b")));
			case "usage_update" -> List.of(new AcpSchema.UsageUpdate("usage_update", 100L, 1000L,
					new AcpSchema.Cost(0.01, "USD"), null));
			case "session_info_update" -> List.of(new AcpSchema.SessionInfoUpdate("interop title", null));
			case "unknown" -> List.of(new AcpSchema.UnknownSessionUpdate("interop_future_update", Map.of("x", 1)),
					chunk("after-unknown"));
			default -> null;
		};
		if (updates == null) {
			return Mono.error(new AcpProtocolException(-32602, "unknown directive: #emit " + kind));
		}
		return Flux.fromIterable(updates).concatMap(u -> context.sendUpdate(sid, u)).then(Mono.just(endTurn()));
	}

	static Mono<AcpSchema.PromptResponse> stop(PromptContext context, String reason) {
		AcpSchema.StopReason r = switch (reason) {
			case "end_turn" -> AcpSchema.StopReason.END_TURN;
			case "max_tokens" -> AcpSchema.StopReason.MAX_TOKENS;
			case "max_turn_requests" -> AcpSchema.StopReason.MAX_TURN_REQUESTS;
			case "refusal" -> AcpSchema.StopReason.REFUSAL;
			case "cancelled" -> AcpSchema.StopReason.CANCELLED;
			default -> null;
		};
		if (r == null) {
			return Mono.error(new AcpProtocolException(-32602, "unknown directive: #stop " + reason));
		}
		return context.sendMessage("stop").thenReturn(new AcpSchema.PromptResponse(r));
	}

	/**
	 * {@code #slow [grace=<ms>]}: a tick every 100 ms. After session/cancel (or session/close) it
	 * keeps ticking for grace ms, then answers cancelled; uncancelled, it answers end_turn after
	 * 10 s. The SDK holds the session's turn until this answers, so a prompt sent meanwhile is
	 * rejected (cancel.prompt-while-cancelling).
	 */
	static Mono<AcpSchema.PromptResponse> slow(PromptContext context, Turn turn, String[] words) {
		long graceMs = 0;
		for (int i = 1; i < words.length; i++) {
			if (words[i].startsWith("grace=")) {
				graceMs = Long.parseLong(words[i].substring(6));
			}
		}
		long t0 = System.nanoTime();
		long grace = graceMs * 1_000_000;
		return Flux.interval(Duration.ZERO, Duration.ofMillis(100))
			.takeUntil(i -> turn.cancelled() && System.nanoTime() - turn.cancelledAtNanos >= grace
					|| System.nanoTime() - t0 >= 10_000_000_000L)
			.concatMap(i -> turn.cancelled() && System.nanoTime() - turn.cancelledAtNanos >= grace ? Mono.just(i)
					: context.sendMessage("tick").thenReturn(i))
			.then(Mono.fromCallable(() -> {
				if (!turn.cancelled()) {
					return endTurn();
				}
				if (!turn.closed && grace == 0) {
					step("cancel.prompt", true, t0, "session/cancel arrived for the running session");
				}
				return cancelled();
			}));
	}

	static boolean terminalAllowed(AcpSchema.ClientCapabilities caps) {
		return caps != null && Boolean.TRUE.equals(caps.terminal());
	}

	static Mono<AcpSchema.PromptResponse> terminalRun(String sid, PromptContext context, AcpSchema.ClientCapabilities caps,
			String[] words) {
		long t0 = System.nanoTime();
		if (!terminalAllowed(caps) || words.length < 3) {
			step("term.run", false, t0, "the client did not advertise terminal");
			return context.sendMessage("terminal error capability").thenReturn(endTurn());
		}
		List<String> args = List.of(words).subList(3, words.length);
		return context.createTerminal(new AcpSchema.CreateTerminalRequest(sid, words[2], args, null, null, null))
			.flatMap(created -> {
				String tid = created.terminalId();
				return context.waitForTerminalExit(new AcpSchema.WaitForTerminalExitRequest(sid, tid))
					.flatMap(exit -> context.getTerminalOutput(new AcpSchema.TerminalOutputRequest(sid, tid))
						.flatMap(out -> context.releaseTerminal(new AcpSchema.ReleaseTerminalRequest(sid, tid))
							.thenReturn(new Object[] { out.output(), exit.exitCode() })));
			})
			.map(r -> {
				String output = r[0] == null ? "" : ((String) r[0]).trim();
				Integer exit = (Integer) r[1];
				step("term.run", output.contains("hi") && Integer.valueOf(0).equals(exit), t0,
						"output " + quote(output) + " exitCode " + exit);
				return "terminal: " + output + " exit=" + exit;
			})
			.onErrorResume(e -> {
				step("term.run", false, t0, "terminal failed: " + e);
				return Mono.just("terminal error " + code(e));
			})
			.flatMap(context::sendMessage)
			.thenReturn(endTurn());
	}

	static Mono<AcpSchema.PromptResponse> terminalKill(String sid, PromptContext context,
			AcpSchema.ClientCapabilities caps, String[] words) {
		long t0 = System.nanoTime();
		if (!terminalAllowed(caps) || words.length < 3) {
			step("term.kill", false, t0, "the client did not advertise terminal");
			return context.sendMessage("terminal error capability").thenReturn(endTurn());
		}
		List<String> args = List.of(words).subList(3, words.length);
		return context.createTerminal(new AcpSchema.CreateTerminalRequest(sid, words[2], args, null, null, null))
			.flatMap(created -> {
				String tid = created.terminalId();
				long[] killedAt = new long[1];
				return Mono.delay(Duration.ofMillis(200))
					.then(context.killTerminal(new AcpSchema.KillTerminalCommandRequest(sid, tid)))
					.then(Mono.fromRunnable(() -> killedAt[0] = System.nanoTime()))
					.then(context.waitForTerminalExit(new AcpSchema.WaitForTerminalExitRequest(sid, tid)))
					.flatMap(exit -> {
						long ms = (System.nanoTime() - killedAt[0]) / 1_000_000;
						step("term.kill", ms <= 5000, t0, "wait_for_exit returned " + ms + " ms after the kill: exitCode "
								+ exit.exitCode() + " signal " + exit.signal());
						return context.releaseTerminal(new AcpSchema.ReleaseTerminalRequest(sid, tid));
					})
					.thenReturn("terminal killed");
			})
			.onErrorResume(e -> {
				step("term.kill", false, t0, "terminal failed: " + e);
				return Mono.just("terminal error " + code(e));
			})
			.flatMap(context::sendMessage)
			.thenReturn(endTurn());
	}

	static Mono<AcpSchema.PromptResponse> elicitForm(String sid, PromptContext context, AcpSchema.ClientCapabilities caps) {
		long t0 = System.nanoTime();
		if (caps == null || caps.elicitation() == null || caps.elicitation().form() == null) {
			step("elicit.form", false, t0, "the client did not advertise elicitation.form");
			return context.sendMessage("elicit error capability").thenReturn(endTurn());
		}
		Map<String, AcpSchema.ElicitationPropertySchema> props = Map.of("name",
				new AcpSchema.StringPropertySchema("string", null, null, null, null, null, null, null, null, null));
		return context
			.createElicitation(AcpSchema.CreateElicitationRequest.form(sid, "interop form",
					new AcpSchema.ElicitationSchema(props, List.of("name"))))
			.map(r -> {
				String action = r.action() == null ? "null" : r.action().name().toLowerCase();
				Object name = r.content() == null ? null : r.content().get("name");
				step("elicit.form", "accept".equals(action) && "interop".equals(name), t0,
						"action " + action + " content " + r.content());
				return "elicit: " + action + " " + toJson(r.content());
			})
			.onErrorResume(e -> {
				step("elicit.form", false, t0, "elicitation/create failed: " + e);
				return Mono.just("elicit error " + code(e));
			})
			.flatMap(context::sendMessage)
			.thenReturn(endTurn());
	}

	static Mono<AcpSchema.PromptResponse> elicitUrl(String sid, PromptContext context, AcpSchema.ClientCapabilities caps) {
		if (caps == null || caps.elicitation() == null || caps.elicitation().url() == null) {
			return context.sendMessage("elicit error capability").thenReturn(endTurn());
		}
		return context
			.createElicitation(AcpSchema.CreateElicitationRequest.url(sid, "interop url", "elic-1",
					"https://example.invalid/interop"))
			.then(context.completeElicitation(new AcpSchema.CompleteElicitationNotification("elic-1")))
			.thenReturn("elicit url done")
			.onErrorResume(e -> Mono.just("elicit error " + code(e)))
			.flatMap(context::sendMessage)
			.thenReturn(endTurn());
	}

	static Mono<AcpSchema.PromptResponse> meta(PromptContext context, AcpSchema.PromptRequest request) {
		Map<String, Object> meta = request.meta() == null ? null : new HashMap<>(request.meta());
		return context
			.sendUpdate(context.getSessionId(),
					new AcpSchema.AgentMessageChunk("agent_message_chunk", new AcpSchema.TextContent("meta"), null, meta))
			.thenReturn(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN, meta));
	}

	static Mono<AcpSchema.PromptResponse> echoCaps(PromptContext context, AcpSchema.InitializeRequest init) {
		long t0 = System.nanoTime();
		AcpSchema.ClientCapabilities caps = init == null ? null : init.clientCapabilities();
		String json;
		try {
			json = caps == null ? "null" : JSON.writeValueAsString(caps);
		}
		catch (Exception e) {
			step("init.client-capabilities", false, t0, "could not serialize the client capabilities: " + e);
			return Mono.error(new AcpProtocolException(-32603, "could not serialize the client capabilities"));
		}
		step("init.client-capabilities", caps != null, t0, "clientCapabilities " + json);
		return context.sendMessage(json).thenReturn(endTurn());
	}

	static Mono<AcpSchema.PromptResponse> big(PromptContext context, String bytes) {
		int n;
		try {
			n = Integer.parseInt(bytes);
		}
		catch (NumberFormatException e) {
			return Mono.error(new AcpProtocolException(-32602, "unknown directive: #big " + bytes));
		}
		return context.sendMessage("x".repeat(n)).thenReturn(endTurn());
	}

	// ---------------------------------------------------------------- helpers

	static String toJson(Object value) {
		try {
			return JSON.writeValueAsString(value);
		}
		catch (Exception e) {
			return String.valueOf(value);
		}
	}

	static int codeOf(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof AcpProtocolException p) {
				return p.getCode();
			}
			if (t instanceof AcpError a) {
				return a.getCode();
			}
		}
		return 0;
	}

	static String code(Throwable e) {
		int c = codeOf(e);
		return c != 0 ? Integer.toString(c) : e.getClass().getSimpleName();
	}

	static String quote(String s) {
		return s == null ? "null" : "\"" + abbreviate(s).replace("\n", "\\n") + "\"";
	}

	/** An agent-side assertion, {@code STEP agent.<id> PASS|FAIL (<ms> ms) -> <detail>}, on stderr. */
	static void step(String id, boolean pass, long t0, String detail) {
		System.err.println("STEP agent." + id + (pass ? " PASS" : " FAIL") + " (" + (System.nanoTime() - t0) / 1_000_000
				+ " ms) -> " + detail.replace('\n', ' ').replace('\r', ' '));
		System.err.flush();
	}

	static void log(String line) {
		System.err.println(line);
	}

	static String abbreviate(String s) {
		return s.length() > 200 ? s.substring(0, 200) + "... (" + s.length() + " chars)" : s;
	}

	static String env(String name, String fallback) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? fallback : v;
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
