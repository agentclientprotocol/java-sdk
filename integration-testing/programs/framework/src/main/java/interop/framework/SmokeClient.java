package interop.framework;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * The client side of the framework smoke cells, shared by every framework program. The framework
 * builds the ACP client bean from its configuration (transport URI and advertised capabilities);
 * this class supplies the handlers through {@link #customize(AcpClient.AsyncSpec)}, which each
 * framework program calls from an {@code AcpClientCustomizer} bean, and then drives the client
 * bean through the catalogue steps in {@code STEPS} with {@link #run(AcpAsyncClient, List)}.
 *
 * <p>Output follows the client contract of integration-testing/README.md: one
 * {@code STEP <id> PASS|FAIL (<ms> ms) -> <detail>} line per step, then
 * {@code RESULT pass=.. fail=.. updates_total=.. upd_<kind>=..}.
 */
public final class SmokeClient {

	static final Duration T = Duration.ofMillis(Long.parseLong(env("STEP_TIMEOUT_MS", "15000")));

	static final Duration UPDATE_GRACE = Duration.ofMillis(1000);

	static final String FS_READ_CONTENT = "line1\nline2\nline3\n";

	private final Map<String, List<String>> chunks = new ConcurrentHashMap<>();

	private final Map<String, AtomicInteger> permissionRequests = new ConcurrentHashMap<>();

	private final AtomicInteger updatesTotal = new AtomicInteger();

	private final Map<String, AtomicInteger> updatesByKind = new ConcurrentHashMap<>();

	private AcpAsyncClient client;

	private AcpSchema.InitializeResponse initResponse;

	private Path dir;

	private int pass;

	private int fail;

	/** Registers the handlers the steps need; called from the framework's customizer bean. */
	public void customize(AcpClient.AsyncSpec spec) {
		spec.sessionUpdateConsumer(n -> {
			updatesTotal.incrementAndGet();
			String kind = n.update() == null ? "other"
					: n.update().getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
			updatesByKind.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
			if (n.update() instanceof AcpSchema.AgentMessageChunk chunk
					&& chunk.content() instanceof AcpSchema.TextContent text) {
				chunks(n.sessionId()).add(text.text());
			}
			return Mono.empty();
		});
		spec.requestPermissionHandler(request -> {
			permissionRequests.computeIfAbsent(request.sessionId(), k -> new AtomicInteger()).incrementAndGet();
			String option = request.options().stream()
				.filter(o -> o.kind() == AcpSchema.PermissionOptionKind.ALLOW_ONCE)
				.findFirst()
				.orElse(request.options().get(0))
				.optionId();
			return Mono.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected(option), null));
		});
		spec.readTextFileHandler(request -> Mono.fromCallable(() -> {
			List<String> lines = Files.readAllLines(Path.of(request.path()), StandardCharsets.UTF_8);
			int from = request.line() == null ? 0 : Math.max(0, request.line() - 1);
			int to = request.limit() == null ? lines.size() : Math.min(lines.size(), from + request.limit());
			StringBuilder out = new StringBuilder();
			for (String line : lines.subList(Math.min(from, lines.size()), to)) {
				out.append(line).append('\n');
			}
			return new AcpSchema.ReadTextFileResponse(out.toString());
		}));
		spec.createElicitationHandler(request -> Mono.just(new AcpSchema.CreateElicitationResponse(
				AcpSchema.ElicitationAction.ACCEPT, Map.of("name", "interop"), null)));
	}

	/** Runs the steps on the framework's client bean and prints the RESULT line. */
	public void run(AcpAsyncClient client, List<String> steps) throws Exception {
		this.client = client;
		this.dir = Files.createTempDirectory("smoke-client-");
		for (String id : steps) {
			step(id);
		}
		StringBuilder result = new StringBuilder("RESULT pass=" + pass + " fail=" + fail + " updates_total="
				+ updatesTotal.get());
		new TreeMap<>(updatesByKind).forEach((k, v) -> result.append(" upd_").append(k).append('=').append(v.get()));
		System.out.println(result);
		System.out.flush();
	}

	private void step(String id) {
		long t0 = System.nanoTime();
		String outcome;
		String detail;
		try {
			detail = switch (id) {
				case "init.initialize" -> initInitialize();
				case "init.agent-info" -> initAgentInfo();
				case "session.new" -> "sessionId=" + newSession();
				case "update.agent_message_chunk" -> agentMessageChunk();
				case "perm.selected" -> permSelected();
				case "fs.read" -> fsRead();
				case "elicit.form" -> elicitForm();
				case "cancel.prompt" -> cancelPrompt();
				case "ext.client-request" -> extClientRequest();
				case "conn.close" -> connClose();
				default -> throw new StepFailure("unknown-step");
			};
			outcome = "PASS";
			pass++;
		}
		catch (Throwable e) {
			detail = e instanceof StepFailure ? e.getMessage() : timeout(e) ? "TIMEOUT " + e : e.toString();
			outcome = "FAIL";
			fail++;
		}
		long ms = "unknown-step".equals(detail) ? 0 : (System.nanoTime() - t0) / 1_000_000;
		System.out.println("STEP " + id + " " + outcome + " (" + ms + " ms) -> " + abbreviate(detail.replace('\n', ' ')));
		System.out.flush();
	}

	private String initInitialize() {
		initResponse = block(client.initialize());
		check(initResponse.protocolVersion() != null && initResponse.protocolVersion() == 1,
				"protocolVersion " + initResponse.protocolVersion());
		return "protocolVersion=1 agentInfo=" + initResponse.agentInfo();
	}

	private String initAgentInfo() {
		check(initResponse != null, "no initialize response: init.initialize did not pass");
		AcpSchema.Implementation info = initResponse.agentInfo();
		check(info != null && info.name() != null && info.name().startsWith("interop-"), "agentInfo " + info);
		return "agentInfo " + info.name();
	}

	private String newSession() {
		String sid = block(client.newSession(new AcpSchema.NewSessionRequest(dir.toString(), List.of()))).sessionId();
		check(sid != null && !sid.isEmpty(), "empty sessionId");
		return sid;
	}

	private AcpSchema.PromptResponse prompt(String sid, String text) {
		return block(client.prompt(AcpSchema.PromptRequest.text(sid, text)));
	}

	private String agentMessageChunk() throws InterruptedException {
		String sid = newSession();
		AcpSchema.PromptResponse r = prompt(sid, "hello");
		check(AcpSchema.StopReason.END_TURN.equals(r.stopReason()), "stopReason " + r.stopReason());
		await(() -> String.join("", chunks(sid)).equals("echo: hello"), UPDATE_GRACE,
				"chunks " + chunks(sid) + " do not spell \"echo: hello\"");
		return "end_turn; chunks spell \"echo: hello\"";
	}

	private String permSelected() throws InterruptedException {
		String sid = newSession();
		AcpSchema.PromptResponse r = prompt(sid, "#permission allow");
		check(AcpSchema.StopReason.END_TURN.equals(r.stopReason()), "stopReason " + r.stopReason());
		await(() -> chunks(sid).contains("permission: selected allow"), UPDATE_GRACE,
				"no chunk \"permission: selected allow\" in " + chunks(sid));
		int asked = permissionRequests.getOrDefault(sid, new AtomicInteger()).get();
		check(asked == 1, asked + " permission requests, expected 1");
		return "one permission request; selected allow; end_turn";
	}

	private String fsRead() throws Exception {
		Path file = dir.resolve("fs-read.txt");
		Files.writeString(file, FS_READ_CONTENT, StandardCharsets.UTF_8);
		String sid = newSession();
		AcpSchema.PromptResponse r = prompt(sid, "#fs read " + file);
		check(AcpSchema.StopReason.END_TURN.equals(r.stopReason()), "stopReason " + r.stopReason());
		await(() -> chunks(sid).contains(FS_READ_CONTENT), UPDATE_GRACE, "chunks " + chunks(sid));
		return "the agent read the file through the client's fs/read_text_file handler";
	}

	private String elicitForm() throws InterruptedException {
		String sid = newSession();
		AcpSchema.PromptResponse r = prompt(sid, "#elicit form");
		check(AcpSchema.StopReason.END_TURN.equals(r.stopReason()), "stopReason " + r.stopReason());
		await(() -> chunks(sid).stream().anyMatch(s -> s.startsWith("elicit: ")), UPDATE_GRACE,
				"chunks " + chunks(sid));
		String chunk = chunks(sid).stream().filter(s -> s.startsWith("elicit: ")).findFirst().orElseThrow();
		check(chunk.replace(" ", "").equals("elicit:accept{\"name\":\"interop\"}"), "chunk " + chunk);
		return "chunk " + chunk;
	}

	private String cancelPrompt() throws Exception {
		String sid = newSession();
		CompletableFuture<AcpSchema.PromptResponse> slow = client.prompt(AcpSchema.PromptRequest.text(sid, "#slow"))
			.toFuture();
		await(() -> chunks(sid).contains("tick"), T, "no tick");
		client.cancel(new AcpSchema.CancelNotification(sid)).block(T);
		long t0 = System.nanoTime();
		AcpSchema.PromptResponse r = slow.get(5, TimeUnit.SECONDS);
		check(AcpSchema.StopReason.CANCELLED.equals(r.stopReason()), "stopReason " + r.stopReason());
		return "stopReason cancelled " + (System.nanoTime() - t0) / 1_000_000 + " ms after the cancel";
	}

	private String extClientRequest() {
		Object result = block(client.sendExtRequest("_interop/ping", Map.of("n", 1)));
		check(result instanceof Map<?, ?> m && m.size() == 1 && m.get("pong") instanceof Number n
				&& n.longValue() == 1, "result " + result);
		return "result " + result;
	}

	private String connClose() {
		client.closeGracefully().block(T);
		return "closed";
	}

	private List<String> chunks(String sid) {
		return chunks.computeIfAbsent(sid, k -> new CopyOnWriteArrayList<>());
	}

	private static <V> V block(Mono<V> mono) {
		V value = mono.block(T);
		check(value != null, "empty response");
		return value;
	}

	private static void await(BooleanSupplier condition, Duration within, String failure) throws InterruptedException {
		long deadline = System.nanoTime() + within.toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new StepFailure(failure);
			}
			Thread.sleep(20);
		}
	}

	private static void check(boolean condition, String failure) {
		if (!condition) {
			throw new StepFailure(failure);
		}
	}

	private static boolean timeout(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof java.util.concurrent.TimeoutException || String.valueOf(t.getMessage()).contains("Timeout")) {
				return true;
			}
		}
		return false;
	}

	private static String abbreviate(String s) {
		return s.length() > 300 ? s.substring(0, 300) + "... (" + s.length() + " chars)" : s;
	}

	static String env(String name, String fallback) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? fallback : v;
	}

	/** Parses {@code --transport http|ws --url <url>}; exits 2 on anything else. */
	public static String[] args(String[] args) {
		String transport = null;
		String url = null;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--transport" -> transport = i + 1 < args.length ? args[++i] : null;
				case "--url" -> url = i + 1 < args.length ? args[++i] : null;
				default -> transport = null;
			}
		}
		if (url == null || !("http".equals(transport) || "ws".equals(transport))) {
			System.err.println("usage: client.sh --transport http|ws --url <url>   (env STEPS)");
			System.exit(2);
		}
		return new String[] { transport, url };
	}

	/** The STEPS environment variable, split. */
	public static List<String> steps() {
		String steps = System.getenv("STEPS");
		if (steps == null || steps.isBlank()) {
			System.err.println("STEPS is not set");
			System.exit(2);
		}
		return List.of(steps.split(","));
	}

	static final class StepFailure extends RuntimeException {

		StepFailure(String message) {
			super(message, null, false, false);
		}

	}

}
