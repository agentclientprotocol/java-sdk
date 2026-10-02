package interop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The Java interop client of the step catalogue (integration-testing/steps.json). Runs the step
 * ids in {@code STEPS} (comma separated) in order and prints one
 * {@code STEP <id> PASS|FAIL (<ms> ms) -> <detail>} line per step, then
 * {@code RESULT pass=.. fail=.. updates_total=.. upd_<kind>=..}.
 *
 * <pre>
 *   STEPS=... launch/client.sh --transport stdio            spawns bash -c "exec $AGENT_CMD"
 *   STEPS=... launch/client.sh --transport http --url http://127.0.0.1:&lt;p&gt;/acp
 *   STEPS=... launch/client.sh --transport ws   --url ws://127.0.0.1:&lt;p&gt;/acp
 * </pre>
 *
 * On stdio the agent's stderr is relayed to stdout: lines starting with {@code STEP } verbatim,
 * every other line prefixed {@code agent| }. Exits 0 once RESULT is printed, whatever the step
 * outcomes.
 *
 * <p>
 * The client advertises what it implements: {@code fs} read and write, {@code terminal} (real
 * processes), {@code elicitation} form and url, {@code auth.terminal} and
 * {@code session.configOptions.boolean}. Steps that need what the Java SDK lacks still run as far as they can and then fail with the
 * Phase B item in their detail (e.g. {@code P9: ...}).
 * </p>
 *
 * <p>
 * {@code --mode raw} (with {@code --transport stdio}) runs the conformance steps {@code raw.*}
 * against a raw driver agent; see {@link Raw}.
 * </p>
 */
public class Client {

	static final Duration T = Duration.ofMillis(Long.parseLong(env("STEP_TIMEOUT_MS", "15000")));

	static final Duration UPDATE_GRACE = Duration.ofMillis(1000);

	static final Duration REPLAY_GRACE = Duration.ofMillis(2000);

	static final String FS_READ_CONTENT = "line1\nline2\nline3\n";

	static final AcpJsonMapper JSON = AcpJsonMapper.createDefault();

	static String transport;

	static String url;

	static String mode = "catalogue";

	static Path dir;

	static int pass;

	static int fail;

	static final AtomicInteger updatesTotal = new AtomicInteger();

	static final Map<String, AtomicInteger> updatesByKind = new ConcurrentHashMap<>();

	/** The main connection, opened by init.initialize. */
	static Conn main;

	static AcpSchema.InitializeResponse initResponse;

	public static void main(String[] args) throws Exception {
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--transport" -> transport = value(args, ++i);
				case "--url" -> url = value(args, ++i);
				case "--mode" -> mode = value(args, ++i);
				default -> usage("unknown argument " + args[i]);
			}
		}
		if (transport == null || !List.of("stdio", "http", "ws").contains(transport)) {
			usage("--transport stdio|http|ws is required");
		}
		if (!List.of("catalogue", "raw").contains(mode)) {
			usage("--mode catalogue|raw");
		}
		if (!transport.equals("stdio") && url == null) {
			usage("--url is required for " + transport);
		}
		if (transport.equals("stdio") && System.getenv("AGENT_CMD") == null) {
			usage("AGENT_CMD is required for stdio");
		}
		String steps = System.getenv("STEPS");
		if (steps == null || steps.isBlank()) {
			usage("STEPS is required");
		}
		dir = Files.createTempDirectory("acp-interop-");
		for (String id : steps.split(",")) {
			if (!id.isBlank()) {
				run(id.trim());
			}
		}
		if (main != null) {
			main.client.close();
		}
		printResult();
		System.exit(0);
	}

	static String value(String[] args, int i) {
		if (i >= args.length) {
			usage("missing value for " + args[i - 1]);
		}
		return args[i];
	}

	static void usage(String problem) {
		System.err.println("client: " + problem);
		System.err.println("usage: STEPS=<ids> client --transport stdio (with AGENT_CMD) | --transport http|ws --url <url>"
				+ " [--mode catalogue|raw]");
		System.exit(2);
	}

	interface Body {

		String run() throws Exception;

	}

	static final Map<String, Body> STEPS = new java.util.LinkedHashMap<>();

	static {
		STEPS.put("init.initialize", Client::initInitialize);
		STEPS.put("init.agent-capabilities", Client::initAgentCapabilities);
		STEPS.put("init.client-capabilities", Client::initClientCapabilities);
		STEPS.put("init.auth-methods", Client::initAuthMethods);
		STEPS.put("init.agent-info", Client::initAgentInfo);
		STEPS.put("init.config-boolean", Client::initConfigBoolean);
		STEPS.put("auth.authenticate", Client::authAuthenticate);
		STEPS.put("auth.logout", Client::authLogout);
		STEPS.put("auth.logout-capability", Client::authLogoutCapability);
		STEPS.put("auth.terminal", Client::authTerminal);
		STEPS.put("session.new", Client::sessionNew);
		STEPS.put("session.load", Client::sessionLoad);
		STEPS.put("session.load-replay", Client::sessionLoadReplay);
		STEPS.put("session.resume", Client::sessionResume);
		STEPS.put("session.list", Client::sessionList);
		STEPS.put("session.close", Client::sessionClose);
		STEPS.put("session.delete", Client::sessionDelete);
		STEPS.put("session.multi", Client::sessionMulti);
		STEPS.put("session.fork", Client::sessionFork);
		STEPS.put("update.agent_message_chunk", Client::agentMessageChunk);
		STEPS.put("update.user_message_chunk", () -> emit("user_message_chunk",
				u -> u instanceof AcpSchema.UserMessageChunk c && text(c.content()).equals("user-chunk")));
		STEPS.put("update.agent_thought_chunk", () -> emit("agent_thought_chunk",
				u -> u instanceof AcpSchema.AgentThoughtChunk c && text(c.content()).equals("thinking")));
		STEPS.put("update.tool_call", () -> emit("tool_call",
				u -> u instanceof AcpSchema.ToolCall c && "call-1".equals(c.toolCallId())
						&& "interop tool".equals(c.title()) && c.kind() == AcpSchema.ToolKind.READ
						// status is optional in the v1 schema (ToolCall requires only toolCallId and
						// title); an absent status is the default, pending (the Rust SDK omits it).
						&& (c.status() == null || c.status() == AcpSchema.ToolCallStatus.PENDING)));
		STEPS.put("update.tool_call_update", Client::toolCallUpdate);
		STEPS.put("update.tool_call-name", Client::toolCallName);
		STEPS.put("update.plan", () -> emit("plan", u -> u instanceof AcpSchema.Plan p && p.entries() != null
				&& p.entries().size() == 2 && planEntry(p.entries().get(0), "step one", "HIGH", "PENDING")
				&& planEntry(p.entries().get(1), "step two", "LOW", "COMPLETED")));
		STEPS.put("update.available_commands_update", () -> emit("available_commands_update",
				u -> u instanceof AcpSchema.AvailableCommandsUpdate a && a.availableCommands() != null
						&& a.availableCommands().size() == 1 && "interop".equals(a.availableCommands().get(0).name())
						&& a.availableCommands().get(0).input() != null
						&& "args".equals(a.availableCommands().get(0).input().hint())));
		STEPS.put("update.current_mode_update", () -> emit("current_mode_update",
				u -> u instanceof AcpSchema.CurrentModeUpdate m && "interop-mode-b".equals(m.currentModeId())));
		STEPS.put("update.config_option_update", () -> emit("config_option_update",
				u -> u instanceof AcpSchema.ConfigOptionUpdate c && "model-b".equals(selectValue(c.configOptions(), "model"))));
		STEPS.put("update.session_info_update", Client::sessionInfoUpdate);
		STEPS.put("update.usage_update", () -> emit("usage_update",
				u -> u instanceof AcpSchema.UsageUpdate x && Long.valueOf(100).equals(x.used())
						&& Long.valueOf(1000).equals(x.size()) && x.cost() != null && x.cost().amount() != null
						&& Math.abs(x.cost().amount() - 0.01) < 1e-9 && "USD".equals(x.cost().currency())));
		STEPS.put("update.unknown", Client::updateUnknown);
		STEPS.put("stop.max_tokens", () -> stop("max_tokens", AcpSchema.StopReason.MAX_TOKENS));
		STEPS.put("stop.refusal", () -> stop("refusal", AcpSchema.StopReason.REFUSAL));
		STEPS.put("stop.max_turn_requests", () -> stop("max_turn_requests", AcpSchema.StopReason.MAX_TURN_REQUESTS));
		STEPS.put("mode.set", Client::modeSet);
		STEPS.put("config.on-new", Client::configOnNew);
		STEPS.put("config.select", Client::configSelect);
		STEPS.put("config.boolean", Client::configBoolean);
		STEPS.put("perm.selected", Client::permSelected);
		STEPS.put("perm.cancelled", Client::permCancelled);
		STEPS.put("fs.write", Client::fsWrite);
		STEPS.put("fs.read", () -> fsRead("#fs read " + fsReadFile(), c -> c.equals(FS_READ_CONTENT)));
		STEPS.put("fs.read-range",
				() -> fsRead("#fs read " + fsReadFile() + " line=2 limit=1", c -> c.trim().equals("line2")));
		STEPS.put("fs.read-missing", () -> fsRead("#fs read " + dir.resolve("no-such-file.txt"),
				c -> c.startsWith("fs read error")));
		STEPS.put("term.run", () -> chunkStep("#terminal run echo hi", "terminal: hi exit=0", T));
		STEPS.put("term.kill", () -> chunkStep("#terminal kill sleep 30", "terminal killed", Duration.ofSeconds(5)));
		STEPS.put("elicit.form", Client::elicitForm);
		STEPS.put("elicit.complete", Client::elicitComplete);
		STEPS.put("cancel.prompt", Client::cancelPrompt);
		STEPS.put("cancel.prompt-while-cancelling", Client::cancelWhileCancelling);
		STEPS.put("cancel.grace", Client::cancelGrace);
		STEPS.put("cancel-request.client", () -> gap("P2", "the Java client cannot send $/cancel_request"));
		STEPS.put("cancel-request.agent", gapped("P2", Client::cancelRequestAgent));
		STEPS.put("cancel-request.unknown", () -> gap("P2", "the Java client cannot send $/cancel_request"));
		STEPS.put("ext.agent-request", Client::extAgentRequest);
		STEPS.put("ext.agent-notification", Client::extAgentNotification);
		STEPS.put("ext.client-request", () -> gap("P9", "AcpAsyncClient has no generic request send"));
		STEPS.put("ext.client-notification", () -> gap("P9", "AcpAsyncClient has no generic notification send"));
		STEPS.put("meta.prompt", Client::metaPrompt);
		STEPS.put("meta.permission", Client::metaPermission);
		STEPS.put("error.method-not-found", Client::methodNotFound);
		STEPS.put("big.prompt-1m", () -> bigPrompt(1 << 20));
		STEPS.put("big.update-1m", () -> bigUpdate(1 << 20));
		STEPS.put("big.prompt-8m", () -> bigPrompt(8 << 20));
		STEPS.put("big.update-8m", () -> bigUpdate(8 << 20));
		STEPS.put("http.reconnect", Client::httpReconnect);
		STEPS.put("stdio.eof-exit", Client::stdioEofExit);
		STEPS.put("conn.close", Client::connClose);
	}

	static void run(String id) {
		Body body = mode.equals("raw") ? Raw.STEPS.get(id) : STEPS.get(id);
		if (body == null) {
			fail++;
			System.out.println("STEP " + id + " FAIL (0 ms) -> unknown-step");
			return;
		}
		step(id, body);
	}

	// ---------------------------------------------------------------- init and auth

	static String initInitialize() {
		main = Conn.open();
		AcpSchema.InitializeResponse r = main.initialize();
		initResponse = r;
		check(r.protocolVersion() != null && r.protocolVersion() == 1, "protocolVersion " + r.protocolVersion());
		return "protocolVersion=1 agentInfo=" + r.agentInfo();
	}

	static AcpSchema.InitializeResponse init() {
		check(initResponse != null, "no initialize response: init.initialize did not pass");
		return initResponse;
	}

	static String initAgentCapabilities() {
		AcpSchema.AgentCapabilities caps = init().agentCapabilities();
		check(caps != null, "no agentCapabilities");
		check(Boolean.TRUE.equals(caps.loadSession()), "loadSession " + caps.loadSession());
		AcpSchema.SessionCapabilities sc = caps.sessionCapabilities();
		check(sc != null, "no sessionCapabilities");
		List<String> missing = new ArrayList<>();
		if (sc.list() == null) {
			missing.add("list");
		}
		if (sc.resume() == null) {
			missing.add("resume");
		}
		if (sc.close() == null) {
			missing.add("close");
		}
		if (sc.delete() == null) {
			missing.add("delete");
		}
		check(missing.isEmpty(), "sessionCapabilities missing " + missing);
		return "loadSession true; sessionCapabilities list, resume, close, delete";
	}

	static Map<String, Object> echoCaps() throws IOException {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#echo-caps");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> !c.chunks(sid).isEmpty(), "no chunk with the echoed capabilities");
		String json = String.join("", c.chunks(sid));
		Map<String, Object> caps = JSON.readValue(json, new TypeRef<Map<String, Object>>() {
		});
		check(caps != null, "the echoed capabilities are null");
		return caps;
	}

	static String initClientCapabilities() throws IOException {
		Map<String, Object> caps = echoCaps();
		check(Boolean.TRUE.equals(path(caps, "fs", "readTextFile")) && Boolean.TRUE.equals(path(caps, "fs", "writeTextFile"))
				&& Boolean.TRUE.equals(path(caps, "terminal")), "echoed capabilities " + caps);
		return "echoed " + caps;
	}

	static String initConfigBoolean() throws IOException {
		Map<String, Object> caps = echoCaps();
		check(path(caps, "session", "configOptions", "boolean") != null,
				"no session.configOptions.boolean in the echoed capabilities " + caps);
		return "echoed session.configOptions.boolean";
	}

	static String initAuthMethods() {
		List<AcpSchema.AuthMethod> methods = init().authMethods();
		check(methods != null && methods.stream().anyMatch(m -> "interop-auth".equals(m.id())), "authMethods " + methods);
		return "authMethods has interop-auth";
	}

	static String authLogoutCapability() {
		AcpSchema.AgentCapabilities caps = init().agentCapabilities();
		check(caps != null && caps.auth() != null && caps.auth().logout() != null,
				"agentCapabilities.auth " + (caps == null ? null : caps.auth()));
		return "agentCapabilities.auth.logout present";
	}

	static String authTerminal() {
		List<AcpSchema.AuthMethod> methods = init().authMethods();
		check(methods != null && methods.stream()
			.anyMatch(m -> m instanceof AcpSchema.AuthMethodTerminal t && "interop-terminal-auth".equals(t.id())),
				"authMethods " + methods);
		return "authMethods has the terminal method interop-terminal-auth";
	}

	static String initAgentInfo() {
		AcpSchema.Implementation info = init().agentInfo();
		check(info != null && info.name() != null && info.name().startsWith("interop-"), "agentInfo " + info);
		return "agentInfo " + info.name();
	}

	static String authAuthenticate() {
		block(main().client.authenticate(new AcpSchema.AuthenticateRequest("interop-auth")));
		return "authenticated with interop-auth";
	}

	static String authLogout() {
		block(main().client.logout(new AcpSchema.LogoutRequest()));
		return "logged out";
	}

	static String gap(String item, String what) {
		throw new StepFailure(item + ": " + what);
	}

	/**
	 * A step blocked by a Java Phase B item: it runs as far as it can, and any failure names the
	 * item, so its expectation ({@code see: "Pn"}) matches whichever way it fails. Once the item
	 * lands the body passes and the stale expectation fails the scenario, as it should.
	 */
	static Body gapped(String item, Body body) {
		return () -> {
			try {
				return body.run();
			}
			catch (Throwable e) {
				String d = describe(e);
				throw new StepFailure(d.startsWith(item + ":") ? d : item + ": " + d);
			}
		};
	}

	// ---------------------------------------------------------------- sessions

	static String sessionNew() {
		String sid = main().newSession();
		check(sid != null && !sid.isEmpty(), "empty sessionId");
		return "sessionId=" + sid;
	}

	static String sessionLoad() {
		Conn c = main();
		String sid = c.newSession();
		c.prompt(sid, "hello load");
		block(c.client.loadSession(new AcpSchema.LoadSessionRequest(sid, dir.toString(), List.of())));
		int before = c.chunks(sid).size();
		AcpSchema.PromptResponse r = c.prompt(sid, "after load");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.chunks(sid).size() > before, "no agent_message_chunk after the load");
		return "loaded " + sid + "; prompt after load end_turn";
	}

	static String sessionLoadReplay() {
		Conn c = main();
		String sid = c.newSession();
		c.prompt(sid, "replay me");
		await(() -> String.join("", c.chunks(sid)).equals("echo: replay me"), "the prompt's own chunks did not arrive");
		int from = c.updates(sid).size();
		block(c.client.loadSession(new AcpSchema.LoadSessionRequest(sid, dir.toString(), List.of())));
		await(() -> {
			List<AcpSchema.SessionUpdate> after = c.updatesFrom(sid, from);
			return after.stream().anyMatch(u -> u instanceof AcpSchema.UserMessageChunk m && text(m.content()).equals("replay me"))
					&& after.stream()
						.anyMatch(u -> u instanceof AcpSchema.AgentMessageChunk m && text(m.content()).contains("replay me"));
		}, () -> "replayed updates " + kinds(c.updatesFrom(sid, from)), REPLAY_GRACE);
		return "replayed " + c.updatesFrom(sid, from).size() + " updates";
	}

	static String sessionResume() throws InterruptedException {
		Conn c = main();
		String sid = c.newSession();
		c.prompt(sid, "before resume");
		await(() -> String.join("", c.chunks(sid)).equals("echo: before resume"), "the prompt's own chunks did not arrive");
		int from = c.updates(sid).size();
		block(c.client.resumeSession(new AcpSchema.ResumeSessionRequest(sid, dir.toString(), List.of())));
		Thread.sleep(UPDATE_GRACE.toMillis());
		List<AcpSchema.SessionUpdate> during = c.updatesFrom(sid, from);
		check(during.isEmpty(), "resume replayed " + kinds(during));
		AcpSchema.PromptResponse r = c.prompt(sid, "after resume");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "after resume: stopReason " + r.stopReason());
		return "resumed without replay; prompt after resume end_turn";
	}

	static String sessionList() throws IOException {
		Conn c = main();
		Path cwd = Files.createDirectories(dir.resolve("list"));
		String sid = c.newSession(cwd.toString());
		String cursor = null;
		for (int page = 0; page < 10; page++) {
			AcpSchema.ListSessionsResponse r = block(
					c.client.listSessions(new AcpSchema.ListSessionsRequest(cwd.toString(), cursor, null)));
			for (AcpSchema.SessionInfo s : r.sessions()) {
				if (sid.equals(s.sessionId())) {
					check(cwd.toString().equals(s.cwd()), "listed with cwd " + s.cwd());
					return "listed " + sid + " with cwd " + s.cwd() + " on page " + (page + 1);
				}
			}
			cursor = r.nextCursor();
			if (cursor == null) {
				break;
			}
		}
		throw new StepFailure(sid + " not listed");
	}

	static String sessionClose() {
		Conn c = main();
		String sid = c.newSession();
		CompletableFuture<AcpSchema.PromptResponse> slow = c.promptAsync(sid, "#slow");
		await(() -> c.chunks(sid).contains("tick"), "no tick", T);
		AcpSchema.CloseSessionResponse closed = block(c.client.closeSession(new AcpSchema.CloseSessionRequest(sid)));
		long t0 = System.nanoTime();
		check(closed != null, "close answered null");
		String ended;
		try {
			AcpSchema.PromptResponse r = slow.get(5, TimeUnit.SECONDS);
			check(r.stopReason() == AcpSchema.StopReason.CANCELLED, "the #slow prompt answered " + r.stopReason());
			ended = "cancelled";
		}
		catch (java.util.concurrent.TimeoutException e) {
			throw new StepFailure("TIMEOUT: the #slow prompt did not end within 5 s of the close");
		}
		catch (java.util.concurrent.ExecutionException e) {
			ended = "error " + describe(e.getCause());
		}
		catch (InterruptedException e) {
			throw new StepFailure("interrupted");
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		try {
			AcpSchema.PromptResponse r = c.prompt(sid, "after close");
			throw new StepFailure("the prompt after close answered " + r.stopReason());
		}
		catch (StepFailure e) {
			throw e;
		}
		catch (RuntimeException e) {
			check(!isTimeout(e), "the prompt after close timed out: TIMEOUT");
			return "close {}; #slow ended (" + ended + ") " + ms + " ms after; prompt after close failed: " + describe(e);
		}
	}

	static String sessionDelete() {
		Conn c = main();
		String sid = c.newSession();
		block(c.client.deleteSession(new AcpSchema.DeleteSessionRequest(sid)));
		block(c.client.deleteSession(new AcpSchema.DeleteSessionRequest("no-such-session")));
		return "deleted " + sid + " and no-such-session";
	}

	static String sessionMulti() {
		Conn c = main();
		String a = c.newSession();
		String b = c.newSession();
		CompletableFuture<AcpSchema.PromptResponse> fa = c.promptAsync(a, "multi A");
		CompletableFuture<AcpSchema.PromptResponse> fb = c.promptAsync(b, "multi B");
		AcpSchema.PromptResponse ra = join(fa);
		AcpSchema.PromptResponse rb = join(fb);
		check(ra.stopReason() == AcpSchema.StopReason.END_TURN && rb.stopReason() == AcpSchema.StopReason.END_TURN,
				"stopReasons " + ra.stopReason() + ", " + rb.stopReason());
		await(() -> String.join("", c.chunks(a)).equals("echo: multi A") && String.join("", c.chunks(b)).equals("echo: multi B"),
				() -> "chunks A " + c.chunks(a) + ", B " + c.chunks(b));
		return "both end_turn; each session got only its own chunks";
	}

	static String sessionFork() {
		Conn c = main();
		String sid = c.newSession();
		c.prompt(sid, "before fork");
		AcpSchema.ForkSessionResponse f = block(
				c.client.forkSession(new AcpSchema.ForkSessionRequest(sid, dir.toString(), List.of())));
		check(f.sessionId() != null && !f.sessionId().equals(sid), "fork returned sessionId " + f.sessionId());
		AcpSchema.PromptResponse r = c.prompt(f.sessionId(), "in fork");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "in fork: stopReason " + r.stopReason());
		return "forked " + sid + " into " + f.sessionId();
	}

	// ---------------------------------------------------------------- updates

	static String agentMessageChunk() {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "hello");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> String.join("", c.chunks(sid)).equals("echo: hello"),
				() -> "chunks " + c.chunks(sid) + " do not spell \"echo: hello\"");
		return "end_turn; chunks spell \"echo: hello\"";
	}

	/** {@code #emit <kind>}: an update matching {@code match} arrives, and the prompt answers end_turn. */
	static String emit(String kind, Predicate<AcpSchema.SessionUpdate> match) {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#emit " + kind);
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.updates(sid).stream().anyMatch(match), () -> "no matching " + kind + " in " + c.updates(sid));
		return kind + " received; end_turn";
	}

	static String toolCallUpdate() {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#emit tool_call_update");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> {
			List<AcpSchema.SessionUpdate> us = c.updates(sid);
			int call = indexOf(us, u -> u instanceof AcpSchema.ToolCall t && "call-1".equals(t.toolCallId()));
			int update = indexOf(us, u -> u instanceof AcpSchema.ToolCallUpdateNotification t && "call-1".equals(t.toolCallId())
					&& t.status() == AcpSchema.ToolCallStatus.COMPLETED && t.content() != null
					&& t.content().stream().anyMatch(x -> x instanceof AcpSchema.ToolCallContentBlock b
							&& text(b.content()).equals("tool output")));
			return call >= 0 && update > call;
		}, () -> "updates " + c.updates(sid));
		return "tool_call call-1, then tool_call_update completed \"tool output\"";
	}

	static String toolCallName() {
		Conn c = main();
		String sid = c.newSession();
		c.prompt(sid, "#emit tool_call name=read_file");
		await(() -> c.updates(sid).stream().anyMatch(u -> u instanceof AcpSchema.ToolCall), "no tool_call");
		List<String> names = c.updates(sid)
			.stream()
			.filter(u -> u instanceof AcpSchema.ToolCall)
			.map(u -> ((AcpSchema.ToolCall) u).name())
			.toList();
		check(names.contains("read_file"), "tool_call names " + names);
		return "tool_call name read_file";
	}

	static String sessionInfoUpdate() {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#emit session_info_update");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.updates(sid).stream().anyMatch(u -> u instanceof AcpSchema.SessionInfoUpdate i
				&& "interop title".equals(i.title())), () -> "no session_info_update titled \"interop title\"; updates "
						+ kinds(c.updates(sid)));
		return "session_info_update title \"interop title\"";
	}

	static String updateUnknown() {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#emit unknown");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.chunks(sid).contains("after-unknown"), () -> "no chunk \"after-unknown\" in " + c.chunks(sid));
		boolean surfaced = c.updates(sid).stream().anyMatch(u -> u instanceof AcpSchema.UnknownSessionUpdate);
		return "after-unknown arrived; end_turn (unknown update " + (surfaced ? "surfaced as UnknownSessionUpdate"
				: "dropped") + ", connection kept)";
	}

	static String stop(String reason, AcpSchema.StopReason expected) {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#stop " + reason);
		check(r.stopReason() == expected, "stopReason " + r.stopReason());
		return "stopReason " + reason;
	}

	// ---------------------------------------------------------------- modes and config

	static String modeSet() {
		Conn c = main();
		AcpSchema.NewSessionResponse n = block(c.client.newSession(new AcpSchema.NewSessionRequest(dir.toString(), List.of())));
		check(n.modes() != null && n.modes().availableModes() != null
				&& n.modes().availableModes().stream().anyMatch(m -> "interop-mode-b".equals(m.id())), "modes " + n.modes());
		block(c.client.setSessionMode(new AcpSchema.SetSessionModeRequest(n.sessionId(), "interop-mode-b")));
		return "session/new listed interop-mode-b; set_mode answered";
	}

	static String configOnNew() {
		Conn c = main();
		AcpSchema.NewSessionResponse r = block(c.client.newSession(new AcpSchema.NewSessionRequest(dir.toString(), List.of())));
		check(r != null && "model-a".equals(selectValue(r.configOptions(), "model")),
				"configOptions " + (r == null ? null : r.configOptions()));
		return "session/new configOptions: model at model-a";
	}

	static String configSelect() {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.SetSessionConfigOptionResponse r = block(c.client.setSessionConfigOption(
				AcpSchema.SetSessionConfigOptionRequest.select(sid, "model", "model-b")));
		check(r != null && "model-b".equals(selectValue(r.configOptions(), "model")), "configOptions " + (r == null ? null : r.configOptions()));
		return "model at model-b in the full list of " + r.configOptions().size();
	}

	static String configBoolean() {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.SetSessionConfigOptionResponse r;
		try {
			r = block(c.client.setSessionConfigOption(AcpSchema.SetSessionConfigOptionRequest.bool(sid, "verbose", true)));
		}
		catch (RuntimeException e) {
			throw new StepFailure("set_config_option verbose failed: " + describe(e));
		}
		check(r != null && r.configOptions() != null && r.configOptions()
			.stream()
			.anyMatch(o -> o instanceof AcpSchema.SessionConfigBoolean b && "verbose".equals(b.id())
					&& Boolean.TRUE.equals(b.currentValue())), "configOptions " + (r == null ? null : r.configOptions()));
		return "verbose at true";
	}

	// ---------------------------------------------------------------- permission, fs, terminal, elicitation

	static String permSelected() {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#permission allow");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.chunks(sid).contains("permission: selected allow"),
				() -> "no chunk \"permission: selected allow\" in " + c.chunks(sid));
		int asked = c.permissionRequests(sid);
		check(asked == 1, asked + " permission requests, expected 1");
		return "one permission request; selected allow; end_turn";
	}

	static String permCancelled() {
		Conn c = main();
		String sid = c.newSession();
		c.cancelOnPermission.add(sid);
		AcpSchema.PromptResponse r = c.prompt(sid, "#permission hold");
		long at = c.cancelSentAt.getOrDefault(sid, 0L);
		check(at != 0, "no permission request arrived, so no cancel was sent");
		long ms = (System.nanoTime() - at) / 1_000_000;
		check(r.stopReason() == AcpSchema.StopReason.CANCELLED, "stopReason " + r.stopReason());
		check(ms <= 5000, "cancelled " + ms + " ms after the cancel");
		return "cancel sent on the permission request; stopReason cancelled " + ms + " ms later";
	}

	static String fsWrite() throws Exception {
		Conn c = main();
		String sid = c.newSession();
		Path file = dir.resolve("fs-write.txt");
		AcpSchema.PromptResponse r = c.prompt(sid, "#fs write " + file + " interop write");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.chunks(sid).contains("fs write ok"), () -> "no chunk \"fs write ok\" in " + c.chunks(sid));
		check(Files.exists(file), file + " was not written");
		String content = Files.readString(file);
		check(content.equals("interop write"), "file content \"" + content + "\"");
		return "written through the client: \"interop write\"";
	}

	static Path fsReadFile() throws IOException {
		Path file = dir.resolve("fs-read.txt");
		Files.writeString(file, FS_READ_CONTENT, StandardCharsets.UTF_8);
		return file;
	}

	static String fsRead(String prompt, Predicate<String> chunk) {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, prompt);
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.chunks(sid).stream().anyMatch(chunk), () -> "chunks " + quoteAll(c.chunks(sid)));
		return "chunk " + quoteAll(c.chunks(sid));
	}

	/** Sends the prompt; the chunk {@code expected} must arrive within {@code within} of the start. */
	static String chunkStep(String prompt, String expected, Duration within) {
		Conn c = main();
		String sid = c.newSession();
		long t0 = System.nanoTime();
		AcpSchema.PromptResponse r = c.prompt(sid, prompt);
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.chunks(sid).contains(expected), () -> "no chunk \"" + expected + "\" in " + quoteAll(c.chunks(sid)));
		long ms = (System.nanoTime() - t0) / 1_000_000;
		check(ms <= within.toMillis(), "\"" + expected + "\" after " + ms + " ms, more than " + within.toMillis());
		return "\"" + expected + "\" after " + ms + " ms";
	}

	static String elicitForm() throws IOException {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#elicit form");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.chunks(sid).stream().anyMatch(s -> s.startsWith("elicit: ")), () -> "chunks " + c.chunks(sid));
		String chunk = c.chunks(sid).stream().filter(s -> s.startsWith("elicit: ")).findFirst().orElseThrow();
		check(chunk.startsWith("elicit: accept "), "chunk " + chunk);
		Object content = JSON.readValue(chunk.substring("elicit: accept ".length()), Object.class);
		check(Map.of("name", "interop").equals(content), "chunk " + chunk);
		return "chunk " + chunk;
	}

	static String elicitComplete() {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#elicit url");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.completedElicitations.contains("elic-1"),
				() -> "no elicitation/complete for elic-1 (received " + c.completedElicitations + "); chunks " + c.chunks(sid));
		return "elicitation/complete elic-1 arrived";
	}

	// ---------------------------------------------------------------- cancellation

	static String cancelPrompt() {
		Conn c = main();
		String sid = c.newSession();
		CompletableFuture<AcpSchema.PromptResponse> slow = c.promptAsync(sid, "#slow");
		await(() -> c.chunks(sid).contains("tick"), "no tick", T);
		block(c.client.cancel(new AcpSchema.CancelNotification(sid)));
		long t0 = System.nanoTime();
		AcpSchema.PromptResponse r = get(slow, Duration.ofSeconds(5), "the prompt did not answer within 5 s of the cancel");
		long ms = (System.nanoTime() - t0) / 1_000_000;
		check(r.stopReason() == AcpSchema.StopReason.CANCELLED, "stopReason " + r.stopReason());
		return "stopReason cancelled " + ms + " ms after the cancel";
	}

	/**
	 * Java SDK rule (G0e, kept by "a cancelled prompt holds its turn until answered"): a prompt sent
	 * while the session's cancelled prompt has not answered yet is rejected with -32600 (invalid request); once it
	 * answers, the next prompt is accepted.
	 */
	static String cancelWhileCancelling() {
		Conn c = main();
		String sid = c.newSession();
		CompletableFuture<AcpSchema.PromptResponse> slow = c.promptAsync(sid, "#slow grace=1000");
		await(() -> c.chunks(sid).contains("tick"), "no tick", T);
		block(c.client.cancel(new AcpSchema.CancelNotification(sid)));
		String during;
		try {
			AcpSchema.PromptResponse r = c.prompt(sid, "during cancel");
			throw new StepFailure("\"during cancel\" was accepted: " + r.stopReason());
		}
		catch (StepFailure e) {
			throw e;
		}
		catch (RuntimeException e) {
			int code = codeOf(e);
			check(code == -32600, "\"during cancel\" failed with " + describe(e) + ", expected -32600");
			during = describe(e);
		}
		AcpSchema.PromptResponse first = get(slow, Duration.ofSeconds(5), "the cancelled prompt did not answer within 5 s");
		check(first.stopReason() == AcpSchema.StopReason.CANCELLED, "the cancelled prompt answered " + first.stopReason());
		AcpSchema.PromptResponse after = c.prompt(sid, "after cancel");
		check(after.stopReason() == AcpSchema.StopReason.END_TURN, "\"after cancel\" answered " + after.stopReason());
		return "first cancelled; \"during cancel\" rejected (" + during + "); \"after cancel\" end_turn";
	}

	static String cancelGrace() throws InterruptedException {
		Conn c = main();
		String sid = c.newSession();
		CompletableFuture<AcpSchema.PromptResponse> hang = c.promptAsync(sid, "#hang");
		Thread.sleep(200);
		block(c.client.cancel(new AcpSchema.CancelNotification(sid)));
		long t0 = System.nanoTime();
		AcpSchema.PromptResponse r = get(hang, Duration.ofSeconds(6), "no answer within 6 s of the cancel");
		long ms = (System.nanoTime() - t0) / 1_000_000;
		check(r.stopReason() == AcpSchema.StopReason.CANCELLED, "stopReason " + r.stopReason());
		return "the SDK answered cancelled " + ms + " ms after the cancel";
	}

	/**
	 * The agent cancels its own fs read with $/cancel_request; the Java client cannot honour it
	 * (P2), so the read handler just waits out its 10 s. The prompt still runs, for the agent's
	 * side of the step, and the step then fails on P2 whatever the agent did.
	 */
	static String cancelRequestAgent() {
		Conn c = main();
		String sid = c.newSession();
		long t0 = System.nanoTime();
		String outcome;
		try {
			outcome = "stopReason " + c.prompt(sid, "#fs read-slow " + dir.resolve("slow.txt")).stopReason();
		}
		catch (RuntimeException e) {
			outcome = describe(e);
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		throw new StepFailure("the Java client cannot honour $/cancel_request; the prompt ended after " + ms + " ms ("
				+ outcome + ") with chunks " + quoteAll(c.chunks(sid)));
	}

	// ---------------------------------------------------------------- extensions, _meta, errors, big

	static String extAgentRequest() throws IOException {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#ext request _interop/ping");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.chunks(sid).stream().anyMatch(s -> s.startsWith("ext: ")), () -> "chunks " + quoteAll(c.chunks(sid)));
		String chunk = c.chunks(sid).stream().filter(s -> s.startsWith("ext: ")).findFirst().orElseThrow();
		Object result;
		try {
			result = JSON.readValue(chunk.substring(5), Object.class);
		}
		catch (IOException e) {
			throw new StepFailure("chunk " + quote(chunk));
		}
		check(result instanceof Map<?, ?> m && m.size() == 1 && Objects.equals(number(m.get("pong")), 1L), "chunk " + quote(chunk));
		return "chunk " + chunk;
	}

	static String extAgentNotification() {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#ext notify _interop/note");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.extNotes.stream().anyMatch(p -> p instanceof Map<?, ?> m && Objects.equals(number(m.get("n")), 1L)),
				() -> "_interop/note notifications " + c.extNotes + "; chunks " + quoteAll(c.chunks(sid)));
		return "_interop/note arrived with {n: 1}";
	}

	static String metaPrompt() {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = block(c.client.prompt(new AcpSchema.PromptRequest(sid,
				List.of(new AcpSchema.TextContent("#meta")), Map.of("interop", "m1"))));
		check(r.meta() != null && "m1".equals(r.meta().get("interop")), "PromptResponse _meta " + r.meta());
		await(() -> c.updates(sid).stream().anyMatch(u -> u instanceof AcpSchema.AgentMessageChunk m
				&& text(m.content()).equals("meta") && m.meta() != null && "m1".equals(m.meta().get("interop"))),
				() -> "updates " + c.updates(sid));
		return "_meta interop=m1 on the chunk and the PromptResponse";
	}

	static String metaPermission() {
		Conn c = main();
		String sid = c.newSession();
		c.prompt(sid, "#permission allow");
		Map<String, Object> meta = c.permissionMeta.get(sid);
		check(meta != null && "m1".equals(meta.get("interop")), "permission request _meta " + meta);
		return "the permission request carried _meta interop == m1";
	}

	static String methodNotFound() {
		Conn c = main();
		try {
			Object r = block(c.client.listProviders(new AcpSchema.ListProvidersRequest(null)));
			throw new StepFailure("providers/list answered " + r);
		}
		catch (StepFailure e) {
			throw e;
		}
		catch (RuntimeException e) {
			check(codeOf(e) == -32601, "providers/list failed with " + describe(e) + ", expected -32601");
		}
		String sid = c.newSession();
		return "providers/list -32601; session/new after it: " + sid;
	}

	static String bigPrompt(int n) {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#len " + "x".repeat(n));
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.chunks(sid).contains("len=" + n), () -> "chunks " + quoteAll(c.chunks(sid)));
		return "len=" + n;
	}

	static String bigUpdate(int n) {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "#big " + n);
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.chunks(sid).stream().anyMatch(s -> s.length() == n),
				() -> "chunk lengths " + c.chunks(sid).stream().map(String::length).toList());
		return "one agent_message_chunk of " + n + " characters";
	}

	// ---------------------------------------------------------------- connections

	static String httpReconnect() {
		check(transport.equals("http"), "http.reconnect does not apply to " + transport);
		Conn first = Conn.open();
		String sid;
		try {
			first.initialize();
			sid = first.newSession();
			AcpSchema.PromptResponse r = first.prompt(sid, "before reconnect");
			check(r.stopReason() == AcpSchema.StopReason.END_TURN, "before reconnect: stopReason " + r.stopReason());
		}
		finally {
			first.close();
		}
		Conn second = Conn.open();
		try {
			second.initialize();
			block(second.client.loadSession(new AcpSchema.LoadSessionRequest(sid, dir.toString(), List.of())));
			AcpSchema.PromptResponse r = second.prompt(sid, "after reconnect");
			check(r.stopReason() == AcpSchema.StopReason.END_TURN, "after reconnect: stopReason " + r.stopReason());
		}
		finally {
			second.close();
		}
		return "session " + sid + " loaded and prompted on a new connection";
	}

	/**
	 * A second agent from AGENT_CMD, driven by hand: the step is about the agent exiting on EOF,
	 * which the SDK transport hides (it ends the child itself).
	 */
	static String stdioEofExit() throws Exception {
		check(transport.equals("stdio"), "stdio.eof-exit does not apply to " + transport);
		Process p = new ProcessBuilder("bash", "-c", "exec " + System.getenv("AGENT_CMD")).start();
		Thread err = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
				for (String line; (line = r.readLine()) != null;) {
					relay("[eof] " + line);
				}
			}
			catch (IOException e) {
				// the child is gone
			}
		}, "eof-agent-stderr");
		err.setDaemon(true);
		err.start();
		try {
			OutputStream in = p.getOutputStream();
			in.write(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,"
					+ "\"clientCapabilities\":{},\"clientInfo\":{\"name\":\"interop-java-client\",\"version\":\"1\"}}}\n")
				.getBytes(StandardCharsets.UTF_8));
			in.flush();
			BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
			CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
				try {
					return out.readLine();
				}
				catch (IOException e) {
					return null;
				}
			});
			String response = line.get(T.toMillis(), TimeUnit.MILLISECONDS);
			check(response != null && response.contains("\"result\""), "initialize answered " + response);
			in.close();
			long t0 = System.nanoTime();
			if (!p.waitFor(5, TimeUnit.SECONDS)) {
				throw new StepFailure("no exit on EOF within 5 s");
			}
			return "initialized, then exited " + (System.nanoTime() - t0) / 1_000_000 + " ms after EOF (code "
					+ p.exitValue() + ")";
		}
		finally {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
		}
	}

	static String connClose() {
		main().close();
		main = null;
		return "closed";
	}

	/** One client connection, with what it received. */
	static final class Conn {

		final AcpAsyncClient client;

		final Map<String, List<AcpSchema.SessionUpdate>> updates = new ConcurrentHashMap<>();

		final Map<String, AtomicInteger> permissions = new ConcurrentHashMap<>();

		/** Per session, the _meta of the last permission request (echoed into the response). */
		final Map<String, Map<String, Object>> permissionMeta = new ConcurrentHashMap<>();

		/** Sessions whose permission request is answered by cancelling the turn (perm.cancelled). */
		final java.util.Set<String> cancelOnPermission = ConcurrentHashMap.newKeySet();

		final Map<String, Long> cancelSentAt = new ConcurrentHashMap<>();

		final List<String> completedElicitations = new CopyOnWriteArrayList<>();

		final List<Object> extNotes = new CopyOnWriteArrayList<>();

		final Terminals terminals = new Terminals();

		final AtomicReference<AcpAsyncClient> self = new AtomicReference<>();

		Conn() {
			AcpClientTransport t = switch (transport) {
				case "http" -> new StreamableHttpAcpClientTransport(URI.create(url), JSON);
				case "ws" -> new WebSocketAcpClientTransport(URI.create(url), JSON);
				default -> {
					StdioAcpClientTransport stdio = new StdioAcpClientTransport(
							AgentParameters.builder("bash").args("-c", "exec " + System.getenv("AGENT_CMD")).build(), JSON);
					stdio.setStdErrorHandler(Client::relay);
					yield stdio;
				}
			};
			this.client = AcpClient.async(t)
				.requestTimeout(T)
				.clientCapabilities(capabilities())
				.sessionUpdateConsumer(n -> {
					this.updates.computeIfAbsent(n.sessionId(), k -> new CopyOnWriteArrayList<>()).add(n.update());
					updatesTotal.incrementAndGet();
					updatesByKind.computeIfAbsent(kind(n.update()), k -> new AtomicInteger()).incrementAndGet();
					System.out.println("  update " + n.sessionId() + ": " + abbreviate(String.valueOf(n.update())));
					return Mono.empty();
				})
				.requestPermissionHandler(this::permission)
				.writeTextFileHandler(req -> Mono.fromCallable(() -> {
					Files.writeString(Path.of(req.path()), req.content(), StandardCharsets.UTF_8);
					System.out.println("  fs/write_text_file " + req.path());
					return new AcpSchema.WriteTextFileResponse();
				}))
				.readTextFileHandler(this::readTextFile)
				.createTerminalHandler(this.terminals::create)
				.terminalOutputHandler(this.terminals::output)
				.waitForTerminalExitHandler(this.terminals::waitForExit)
				.killTerminalHandler(this.terminals::kill)
				.releaseTerminalHandler(this.terminals::release)
				.createElicitationHandler(req -> {
					System.out.println("  elicitation/create " + req.mode() + " " + req.message());
					return Mono.just("url".equals(req.mode()) ? new AcpSchema.CreateElicitationResponse(
							AcpSchema.ElicitationAction.ACCEPT, null, null)
							: AcpSchema.CreateElicitationResponse.accept(Map.of("name", "interop")));
				})
				// No typed handler for elicitation/complete yet (P4); the generic one receives it.
				.notificationHandler(AcpSchema.METHOD_ELICITATION_COMPLETE, params -> {
					Object id = params instanceof Map<?, ?> m ? m.get("elicitationId") : null;
					System.out.println("  elicitation/complete " + params);
					this.completedElicitations.add(String.valueOf(id));
					return Mono.empty();
				})
				.requestHandler("_interop/ping", params -> {
					System.out.println("  _interop/ping " + params);
					return Mono.just(Map.of("pong", 1));
				})
				.notificationHandler("_interop/note", params -> {
					System.out.println("  _interop/note " + params);
					this.extNotes.add(params);
					return Mono.empty();
				})
				.build();
			this.self.set(this.client);
		}

		static Conn open() {
			return new Conn();
		}

		static AcpSchema.ClientCapabilities capabilities() {
			return new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(true, true), true,
					new AcpSchema.ClientSessionCapabilities(AcpSchema.SessionConfigOptionsCapabilities.withBoolean()),
					new AcpSchema.AuthCapabilities(true), new AcpSchema.ElicitationCapabilities(Map.of(), Map.of(), null),
					null);
		}

		Mono<AcpSchema.RequestPermissionResponse> permission(AcpSchema.RequestPermissionRequest req) {
			this.permissions.computeIfAbsent(req.sessionId(), k -> new AtomicInteger()).incrementAndGet();
			if (req.meta() != null) {
				this.permissionMeta.put(req.sessionId(), req.meta());
			}
			System.out.println("  permission request " + req.sessionId() + ": " + req.options());
			if (this.cancelOnPermission.contains(req.sessionId())) {
				return this.client.cancel(new AcpSchema.CancelNotification(req.sessionId()))
					.then(Mono.fromCallable(() -> {
						this.cancelSentAt.put(req.sessionId(), System.nanoTime());
						return new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionCancelled());
					}));
			}
			AcpSchema.PermissionOption chosen = req.options()
				.stream()
				.filter(o -> o.kind() == AcpSchema.PermissionOptionKind.ALLOW_ONCE)
				.findFirst()
				.orElse(req.options().get(0));
			return Mono.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected(chosen.optionId()),
					req.meta()));
		}

		Mono<AcpSchema.ReadTextFileResponse> readTextFile(AcpSchema.ReadTextFileRequest req) {
			Mono<AcpSchema.ReadTextFileResponse> read = Mono.fromCallable(() -> {
				System.out.println("  fs/read_text_file " + req.path() + " line=" + req.line() + " limit=" + req.limit());
				String content;
				try {
					content = Files.readString(Path.of(req.path()), StandardCharsets.UTF_8);
				}
				catch (NoSuchFileException e) {
					throw new AcpProtocolException(-32002, "Resource not found: " + req.path());
				}
				if (req.line() != null || req.limit() != null) {
					List<String> lines = content.lines().toList();
					int from = Math.max(0, (req.line() == null ? 1 : req.line()) - 1);
					int to = req.limit() == null ? lines.size() : Math.min(lines.size(), from + req.limit());
					StringBuilder sb = new StringBuilder();
					for (int i = from; i < to; i++) {
						sb.append(lines.get(i)).append('\n');
					}
					content = sb.toString();
				}
				return new AcpSchema.ReadTextFileResponse(content);
			}).subscribeOn(Schedulers.boundedElastic());
			// cancel-request.agent: a read of slow.txt waits up to 10 s; it is never cancelled, the
			// Java client has no $/cancel_request (P2).
			return req.path().endsWith("slow.txt") ? Mono.delay(Duration.ofSeconds(10)).then(read) : read;
		}

		AcpSchema.InitializeResponse initialize() {
			return block(this.client.initialize(new AcpSchema.InitializeRequest(1, capabilities(),
					new AcpSchema.Implementation("interop-java-client", "1"), null)));
		}

		String newSession() {
			return newSession(dir.toString());
		}

		String newSession(String cwd) {
			return block(this.client.newSession(new AcpSchema.NewSessionRequest(cwd, List.of()))).sessionId();
		}

		AcpSchema.PromptResponse prompt(String sid, String text) {
			return block(this.client.prompt(new AcpSchema.PromptRequest(sid, List.of(new AcpSchema.TextContent(text)))));
		}

		CompletableFuture<AcpSchema.PromptResponse> promptAsync(String sid, String text) {
			return this.client.prompt(new AcpSchema.PromptRequest(sid, List.of(new AcpSchema.TextContent(text))))
				.timeout(T.plus(T))
				.toFuture();
		}

		List<AcpSchema.SessionUpdate> updates(String sid) {
			return List.copyOf(this.updates.getOrDefault(sid, List.of()));
		}

		List<AcpSchema.SessionUpdate> updatesFrom(String sid, int from) {
			List<AcpSchema.SessionUpdate> all = updates(sid);
			return from >= all.size() ? List.of() : all.subList(from, all.size());
		}

		/** The text of every agent_message_chunk received for the session, in order. */
		List<String> chunks(String sid) {
			List<String> out = new ArrayList<>();
			for (AcpSchema.SessionUpdate u : updates(sid)) {
				if (u instanceof AcpSchema.AgentMessageChunk c && c.content() instanceof AcpSchema.TextContent t) {
					out.add(t.text());
				}
			}
			return out;
		}

		int permissionRequests(String sid) {
			AtomicInteger n = this.permissions.get(sid);
			return n == null ? 0 : n.get();
		}

		void close() {
			try {
				block(this.client.closeGracefully());
			}
			finally {
				this.terminals.releaseAll();
			}
		}

	}

	/** The client's terminals: real processes, output collected in memory. */
	static final class Terminals {

		record Term(Process process, StringBuffer output) {
		}

		final Map<String, Term> terms = new ConcurrentHashMap<>();

		final AtomicLong ids = new AtomicLong();

		Mono<AcpSchema.CreateTerminalResponse> create(AcpSchema.CreateTerminalRequest req) {
			return Mono.fromCallable(() -> {
				List<String> cmd = new ArrayList<>();
				cmd.add(req.command());
				if (req.args() != null) {
					cmd.addAll(req.args());
				}
				ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
				if (req.cwd() != null) {
					pb.directory(Path.of(req.cwd()).toFile());
				}
				if (req.env() != null) {
					req.env().forEach(e -> pb.environment().put(e.name(), e.value()));
				}
				Process p = pb.start();
				p.getOutputStream().close();
				StringBuffer out = new StringBuffer();
				Thread reader = new Thread(() -> {
					try (var in = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
						char[] buf = new char[4096];
						for (int n; (n = in.read(buf)) > 0;) {
							out.append(buf, 0, n);
						}
					}
					catch (IOException e) {
						// process gone
					}
				}, "terminal-output");
				reader.setDaemon(true);
				reader.start();
				String id = "term-" + this.ids.incrementAndGet();
				this.terms.put(id, new Term(p, out));
				System.out.println("  terminal/create " + id + " " + cmd);
				return new AcpSchema.CreateTerminalResponse(id);
			}).subscribeOn(Schedulers.boundedElastic());
		}

		Term term(String id) {
			Term t = this.terms.get(id);
			if (t == null) {
				throw new AcpProtocolException(-32602, "unknown terminal " + id);
			}
			return t;
		}

		Mono<AcpSchema.TerminalOutputResponse> output(AcpSchema.TerminalOutputRequest req) {
			return Mono.fromCallable(() -> {
				Term t = term(req.terminalId());
				AcpSchema.TerminalExitStatus status = t.process().isAlive() ? null
						: new AcpSchema.TerminalExitStatus(t.process().exitValue(), null);
				return new AcpSchema.TerminalOutputResponse(t.output().toString(), false, status);
			});
		}

		Mono<AcpSchema.WaitForTerminalExitResponse> waitForExit(AcpSchema.WaitForTerminalExitRequest req) {
			return Mono.fromCallable(() -> {
				Term t = term(req.terminalId());
				int code = t.process().waitFor();
				// let the reader drain what the process wrote before it exited
				Thread.sleep(50);
				return new AcpSchema.WaitForTerminalExitResponse(code, null);
			}).subscribeOn(Schedulers.boundedElastic());
		}

		Mono<AcpSchema.KillTerminalCommandResponse> kill(AcpSchema.KillTerminalCommandRequest req) {
			return Mono.fromCallable(() -> {
				Term t = term(req.terminalId());
				t.process().descendants().forEach(ProcessHandle::destroy);
				t.process().destroy();
				System.out.println("  terminal/kill " + req.terminalId());
				return new AcpSchema.KillTerminalCommandResponse();
			});
		}

		Mono<AcpSchema.ReleaseTerminalResponse> release(AcpSchema.ReleaseTerminalRequest req) {
			return Mono.fromCallable(() -> {
				Term t = this.terms.remove(req.terminalId());
				if (t != null && t.process().isAlive()) {
					t.process().destroyForcibly();
				}
				return new AcpSchema.ReleaseTerminalResponse();
			});
		}

		void releaseAll() {
			this.terms.values().forEach(t -> t.process().destroyForcibly());
			this.terms.clear();
		}

	}

	static Conn main() {
		check(main != null, "no main connection: init.initialize did not run first");
		return main;
	}

	static String kind(AcpSchema.SessionUpdate u) {
		if (u instanceof AcpSchema.UserMessageChunk) {
			return "user_message_chunk";
		}
		if (u instanceof AcpSchema.AgentMessageChunk) {
			return "agent_message_chunk";
		}
		if (u instanceof AcpSchema.AgentThoughtChunk) {
			return "agent_thought_chunk";
		}
		if (u instanceof AcpSchema.ToolCall) {
			return "tool_call";
		}
		if (u instanceof AcpSchema.ToolCallUpdateNotification) {
			return "tool_call_update";
		}
		if (u instanceof AcpSchema.Plan) {
			return "plan";
		}
		if (u instanceof AcpSchema.AvailableCommandsUpdate) {
			return "available_commands_update";
		}
		if (u instanceof AcpSchema.CurrentModeUpdate) {
			return "current_mode_update";
		}
		if (u instanceof AcpSchema.UsageUpdate) {
			return "usage_update";
		}
		if (u instanceof AcpSchema.ConfigOptionUpdate) {
			return "config_option_update";
		}
		return "other";
	}

	static List<String> kinds(List<AcpSchema.SessionUpdate> us) {
		return us.stream().map(Client::kind).toList();
	}

	static String text(AcpSchema.ContentBlock c) {
		return c instanceof AcpSchema.TextContent t && t.text() != null ? t.text() : "";
	}

	static boolean planEntry(AcpSchema.PlanEntry e, String content, String priority, String status) {
		return content.equals(e.content()) && e.priority() != null && priority.equals(e.priority().name())
				&& e.status() != null && status.equals(e.status().name());
	}

	static String selectValue(List<AcpSchema.SessionConfigOption> options, String id) {
		if (options == null) {
			return null;
		}
		for (AcpSchema.SessionConfigOption o : options) {
			if (o instanceof AcpSchema.SessionConfigSelect s && id.equals(s.id())) {
				return s.currentValue();
			}
		}
		return null;
	}

	static int indexOf(List<AcpSchema.SessionUpdate> us, Predicate<AcpSchema.SessionUpdate> p) {
		for (int i = 0; i < us.size(); i++) {
			if (p.test(us.get(i))) {
				return i;
			}
		}
		return -1;
	}

	static Object path(Map<String, Object> map, String... keys) {
		Object cur = map;
		for (String k : keys) {
			if (!(cur instanceof Map<?, ?> m)) {
				return null;
			}
			cur = m.get(k);
		}
		return cur;
	}

	static Long number(Object o) {
		return o instanceof Number n && n.doubleValue() == n.longValue() ? n.longValue() : null;
	}

	// ---------------------------------------------------------------- step plumbing

	static final class StepFailure extends RuntimeException {

		StepFailure(String message) {
			super(message);
		}

	}

	static void step(String id, Body body) {
		long t0 = System.nanoTime();
		String outcome;
		String detail;
		try {
			detail = body.run();
			outcome = "PASS";
			pass++;
		}
		catch (Throwable e) {
			detail = describe(e);
			outcome = "FAIL";
			fail++;
		}
		System.out.println("STEP " + id + " " + outcome + " (" + (System.nanoTime() - t0) / 1_000_000 + " ms) -> "
				+ abbreviate(detail.replace('\n', ' ').replace('\r', ' ')));
		System.out.flush();
	}

	static boolean isTimeout(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof java.util.concurrent.TimeoutException || String.valueOf(t).contains("Timeout on blocking read")
					|| String.valueOf(t.getMessage()).contains("TIMEOUT")) {
				return true;
			}
		}
		return false;
	}

	/** A failure detail; a timeout always contains TIMEOUT, so the runner can count hangs. */
	static String describe(Throwable e) {
		if (e instanceof StepFailure) {
			return e.getMessage();
		}
		int code = codeOf(e);
		String s = (code != 0 ? "error " + code + ": " : "") + e;
		if (isTimeout(e)) {
			return "TIMEOUT after " + T.toMillis() + " ms: " + s;
		}
		return s;
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

	static <T> T block(Mono<T> mono) {
		return mono.block(T);
	}

	static <V> V join(CompletableFuture<V> f) {
		return get(f, T, "TIMEOUT");
	}

	static <V> V get(CompletableFuture<V> f, Duration within, String onTimeout) {
		try {
			return f.get(within.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (java.util.concurrent.TimeoutException e) {
			throw new StepFailure("TIMEOUT: " + onTimeout);
		}
		catch (java.util.concurrent.ExecutionException e) {
			throw e.getCause() instanceof RuntimeException r ? r : new RuntimeException(e.getCause());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StepFailure("interrupted");
		}
	}

	static void check(boolean ok, String failure) {
		if (!ok) {
			throw new StepFailure(failure);
		}
	}

	/** Wait up to the update grace for a condition on updates, which may trail the response. */
	static void await(Supplier<Boolean> condition, String failure) {
		await(condition, () -> failure, UPDATE_GRACE);
	}

	static void await(Supplier<Boolean> condition, String failure, Duration within) {
		await(condition, () -> failure, within);
	}

	static void await(Supplier<Boolean> condition, Supplier<String> failure) {
		await(condition, failure, UPDATE_GRACE);
	}

	static void await(Supplier<Boolean> condition, Supplier<String> failure, Duration within) {
		long deadline = System.nanoTime() + within.toNanos();
		while (!condition.get()) {
			if (System.nanoTime() > deadline) {
				throw new StepFailure(failure.get());
			}
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new StepFailure("interrupted");
			}
		}
	}

	/** Relay one stderr line of a stdio agent: STEP lines verbatim, everything else prefixed. */
	static void relay(String line) {
		System.out.println(line.startsWith("STEP ") ? line : "agent| " + line);
	}

	static void printResult() {
		StringBuilder sb = new StringBuilder("RESULT pass=" + pass + " fail=" + fail + " updates_total=" + updatesTotal.get());
		new TreeMap<>(updatesByKind).forEach((k, v) -> sb.append(" upd_").append(k).append('=').append(v.get()));
		System.out.println(sb);
		System.out.flush();
	}

	static String env(String name, String fallback) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? fallback : v;
	}

	static String quote(String s) {
		return s == null ? "null" : "\"" + abbreviate(s).replace("\n", "\\n") + "\"";
	}

	static String quoteAll(List<String> ss) {
		return ss.stream().map(Client::quote).toList().toString();
	}

	static String abbreviate(String s) {
		return s.length() > 300 ? s.substring(0, 300) + "... (" + s.length() + " chars)" : s;
	}

}
