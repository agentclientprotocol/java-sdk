package interop;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

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
 * outcomes. This is the WP0 seed: steps it does not implement yet print
 * {@code STEP <id> FAIL (0 ms) -> unknown-step}.
 */
public class Client {

	static final Duration T = Duration.ofMillis(Long.parseLong(env("STEP_TIMEOUT_MS", "15000")));

	static final Duration UPDATE_GRACE = Duration.ofMillis(1000);

	static String transport;

	static String url;

	static Path dir;

	static int pass;

	static int fail;

	static final AtomicInteger updatesTotal = new AtomicInteger();

	static final Map<String, AtomicInteger> updatesByKind = new ConcurrentHashMap<>();

	/** The main connection, opened by init.initialize. */
	static Conn main;

	public static void main(String[] args) throws Exception {
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--transport" -> transport = args[++i];
				case "--url" -> url = args[++i];
				default -> usage("unknown argument " + args[i]);
			}
		}
		if (transport == null || !List.of("stdio", "http", "ws").contains(transport)) {
			usage("--transport stdio|http|ws is required");
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
			run(id.trim());
		}
		printResult();
		System.exit(0);
	}

	static void usage(String problem) {
		System.err.println("client: " + problem);
		System.err.println("usage: STEPS=<ids> client --transport stdio (with AGENT_CMD) | --transport http|ws --url <url>");
		System.exit(2);
	}

	static void run(String id) {
		switch (id) {
			case "init.initialize" -> step(id, Client::initInitialize);
			case "session.new" -> step(id, Client::sessionNew);
			case "session.load" -> step(id, Client::sessionLoad);
			case "update.agent_message_chunk" -> step(id, Client::agentMessageChunk);
			case "perm.selected" -> step(id, Client::permSelected);
			case "fs.write" -> step(id, Client::fsWrite);
			case "http.reconnect" -> step(id, Client::httpReconnect);
			case "conn.close" -> step(id, Client::connClose);
			default -> {
				fail++;
				System.out.println("STEP " + id + " FAIL (0 ms) -> unknown-step");
			}
		}
	}

	// ---------------------------------------------------------------- steps

	static String initInitialize() {
		main = Conn.open();
		AcpSchema.InitializeResponse r = main.initialize();
		check(r.protocolVersion() != null && r.protocolVersion() == 1, "protocolVersion " + r.protocolVersion());
		return "protocolVersion=1 agentInfo=" + r.agentInfo();
	}

	static String sessionNew() {
		String sid = main().newSession();
		check(sid != null && !sid.isEmpty(), "empty sessionId");
		return "sessionId=" + sid;
	}

	static String sessionLoad() {
		Conn c = main();
		String sid = c.newSession();
		c.prompt(sid, "hello load");
		block(c.client.loadSession(new AcpSchema.LoadSessionRequest(sid, dir.toString(), List.of(), null, null)));
		int before = c.chunks(sid).size();
		AcpSchema.PromptResponse r = c.prompt(sid, "after load");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> c.chunks(sid).size() > before, "no agent_message_chunk after the load");
		return "loaded " + sid + "; prompt after load end_turn";
	}

	static String agentMessageChunk() {
		Conn c = main();
		String sid = c.newSession();
		AcpSchema.PromptResponse r = c.prompt(sid, "hello");
		check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
		await(() -> String.join("", c.chunks(sid)).equals("echo: hello"),
				() -> "chunks " + c.chunks(sid) + " do not spell \"echo: hello\"");
		return "end_turn; chunks spell \"echo: hello\"";
	}

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
			block(second.client.loadSession(new AcpSchema.LoadSessionRequest(sid, dir.toString(), List.of(), null, null)));
			AcpSchema.PromptResponse r = second.prompt(sid, "after reconnect");
			check(r.stopReason() == AcpSchema.StopReason.END_TURN, "after reconnect: stopReason " + r.stopReason());
		}
		finally {
			second.close();
		}
		return "session " + sid + " loaded and prompted on a new connection";
	}

	static String connClose() {
		main().close();
		return "closed";
	}

	// ---------------------------------------------------------------- connection

	/** One client connection, with the updates and permission requests it received. */
	static final class Conn {

		final AcpAsyncClient client;

		final Map<String, List<AcpSchema.SessionUpdate>> updates = new ConcurrentHashMap<>();

		final Map<String, AtomicInteger> permissions = new ConcurrentHashMap<>();

		Conn(AcpAsyncClient client) {
			this.client = client;
		}

		static Conn open() {
			AcpClientTransport t = switch (transport) {
				case "http" -> new StreamableHttpAcpClientTransport(URI.create(url), AcpJsonMapper.createDefault());
				case "ws" -> new WebSocketAcpClientTransport(URI.create(url), AcpJsonMapper.createDefault());
				default -> {
					StdioAcpClientTransport stdio = new StdioAcpClientTransport(AgentParameters.builder("bash")
						.args("-c", "exec " + System.getenv("AGENT_CMD"))
						.build(), AcpJsonMapper.createDefault());
					stdio.setStdErrorHandler(Client::relay);
					yield stdio;
				}
			};
			Conn[] self = new Conn[1];
			AcpAsyncClient client = AcpClient.async(t)
				.requestTimeout(T)
				.clientCapabilities(new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(false, true), false))
				.sessionUpdateConsumer(n -> {
					self[0].updates.computeIfAbsent(n.sessionId(), k -> new CopyOnWriteArrayList<>()).add(n.update());
					updatesTotal.incrementAndGet();
					updatesByKind.computeIfAbsent(kind(n.update()), k -> new AtomicInteger()).incrementAndGet();
					System.out.println("  update " + n.sessionId() + ": " + abbreviate(String.valueOf(n.update())));
					return Mono.empty();
				})
				.requestPermissionHandler(req -> {
					self[0].permissions.computeIfAbsent(req.sessionId(), k -> new AtomicInteger()).incrementAndGet();
					System.out.println("  permission request " + req.sessionId() + ": " + req.options());
					AcpSchema.PermissionOption chosen = req.options()
						.stream()
						.filter(o -> o.kind() == AcpSchema.PermissionOptionKind.ALLOW_ONCE)
						.findFirst()
						.orElse(req.options().get(0));
					return Mono.just(new AcpSchema.RequestPermissionResponse(
							new AcpSchema.PermissionSelected(chosen.optionId())));
				})
				.writeTextFileHandler(req -> Mono.fromCallable(() -> {
					Files.writeString(Path.of(req.path()), req.content(), StandardCharsets.UTF_8);
					System.out.println("  fs/write_text_file " + req.path());
					return new AcpSchema.WriteTextFileResponse();
				}))
				.build();
			self[0] = new Conn(client);
			return self[0];
		}

		AcpSchema.InitializeResponse initialize() {
			return block(client.initialize(new AcpSchema.InitializeRequest(1,
					new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(false, true), false),
					new AcpSchema.Implementation("interop-java-client", "1"), null)));
		}

		String newSession() {
			return block(client.newSession(new AcpSchema.NewSessionRequest(dir.toString(), List.of()))).sessionId();
		}

		AcpSchema.PromptResponse prompt(String sid, String text) {
			return block(client.prompt(new AcpSchema.PromptRequest(sid, List.of(new AcpSchema.TextContent(text)))));
		}

		/** The text of every agent_message_chunk received for the session, in order. */
		List<String> chunks(String sid) {
			List<String> out = new ArrayList<>();
			for (AcpSchema.SessionUpdate u : updates.getOrDefault(sid, List.of())) {
				if (u instanceof AcpSchema.AgentMessageChunk c && c.content() instanceof AcpSchema.TextContent t) {
					out.add(t.text());
				}
			}
			return out;
		}

		int permissionRequests(String sid) {
			AtomicInteger n = permissions.get(sid);
			return n == null ? 0 : n.get();
		}

		void close() {
			block(client.closeGracefully());
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

	// ---------------------------------------------------------------- step plumbing

	interface Body {

		String run() throws Exception;

	}

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
				+ detail.replace('\n', ' ').replace('\r', ' '));
	}

	/** A failure detail; a timeout always contains TIMEOUT, so the runner can count hangs. */
	static String describe(Throwable e) {
		if (e instanceof StepFailure) {
			return e.getMessage();
		}
		String s = String.valueOf(e);
		if (s.contains("Timeout on blocking read") || e instanceof java.util.concurrent.TimeoutException
				|| e.getCause() instanceof java.util.concurrent.TimeoutException) {
			return "TIMEOUT after " + T.toMillis() + " ms: " + s;
		}
		return s;
	}

	static <T> T block(Mono<T> mono) {
		return mono.block(T);
	}

	static void check(boolean ok, String failure) {
		if (!ok) {
			throw new StepFailure(failure);
		}
	}

	/** Wait up to the update grace for a condition on updates, which may trail the response. */
	static void await(Supplier<Boolean> condition, String failure) {
		await(condition, () -> failure);
	}

	static void await(Supplier<Boolean> condition, Supplier<String> failure) {
		long deadline = System.nanoTime() + UPDATE_GRACE.toNanos();
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

	static String abbreviate(String s) {
		return s.length() > 300 ? s.substring(0, 300) + "... (" + s.length() + " chars)" : s;
	}

}
