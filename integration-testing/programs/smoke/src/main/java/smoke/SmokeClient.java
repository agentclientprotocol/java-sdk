package smoke;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * Real-agent smoke client. Spawns a published ACP agent CLI over stdio with
 * {@link StdioAcpClientTransport} and runs {@code initialize}, {@code session/new} and, when the
 * agent has credentials, one short prompt. Prints one {@code STEP <name> PASS|FAIL|SKIP} line per
 * step and a final {@code RESULT} line, like the rest of the suite.
 *
 * <p>
 * Configured from the environment (set by {@code smoke.sh}):
 * <ul>
 * <li>{@code AGENT_CMD}: the agent command line, run as {@code bash -c "exec $AGENT_CMD"}.</li>
 * <li>{@code SMOKE_AGENT}: a display name.</li>
 * <li>{@code SMOKE_AUTH}: {@code key}, {@code login} or {@code none}: whether the agent has
 * credentials. Only with credentials does the prompt step run; without them {@code session/new}
 * may answer -32000 (auth required) and still pass.</li>
 * <li>{@code SMOKE_PROMPT}: the prompt text (default: a one-word reply).</li>
 * <li>{@code SMOKE_TOTAL_TIMEOUT}: seconds before the watchdog kills everything (default 300).</li>
 * </ul>
 *
 * <p>
 * The client never calls {@code authenticate}: on these agents it persists credentials in the
 * user's configuration (Gemini clears its cached Google login, Codex writes its auth file), so a
 * smoke run must not do it. Agents pick up API keys from their environment instead.
 */
public final class SmokeClient {

	static final int AUTH_REQUIRED = -32000;

	static final Duration INIT_TIMEOUT = seconds("SMOKE_INIT_TIMEOUT", 120);

	static final Duration NEW_SESSION_TIMEOUT = seconds("SMOKE_SESSION_TIMEOUT", 90);

	static final Duration PROMPT_TIMEOUT = seconds("SMOKE_PROMPT_TIMEOUT", 180);

	static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(15);

	/** Credential shapes redacted from relayed agent output, on top of the key values themselves. */
	static final Pattern SECRET_SHAPES = Pattern
		.compile("(sk-[A-Za-z0-9_-]{12,}|AIza[0-9A-Za-z_-]{20,}|ya29\\.[0-9A-Za-z_.-]{20,}|eyJ[0-9A-Za-z_-]{20,}\\.[0-9A-Za-z_.-]+)");

	static final List<String> KEY_VARS = List.of("GEMINI_API_KEY", "GOOGLE_API_KEY", "ANTHROPIC_API_KEY",
			"OPENAI_API_KEY", "CODEX_API_KEY");

	static final AtomicInteger updatesTotal = new AtomicInteger();

	static final Map<String, Integer> updateKinds = new TreeMap<>();

	static final StringBuilder agentText = new StringBuilder();

	static int pass;

	static int fail;

	static int skip;

	static final Map<String, String> result = new TreeMap<>();

	interface Step {

		String run() throws Exception;

	}

	/** A step outcome other than PASS that is not an exception. */
	static final class Skip extends RuntimeException {

		Skip(String reason) {
			super(reason);
		}

	}

	public static void main(String[] args) throws Exception {
		// Before anything logs: every line this process prints, SDK log lines included, goes
		// through redact(), so no key reaches a log file or a CI artifact.
		System.setOut(new PrintStream(new RedactingStream(new FileOutputStream(FileDescriptor.out)), true,
				StandardCharsets.UTF_8));
		System.setErr(new PrintStream(new RedactingStream(new FileOutputStream(FileDescriptor.err)), true,
				StandardCharsets.UTF_8));
		String agent = env("SMOKE_AGENT", "agent");
		String cmd = System.getenv("AGENT_CMD");
		if (cmd == null || cmd.isBlank()) {
			System.out.println("STEP setup FAIL -> AGENT_CMD is not set");
			System.exit(2);
		}
		String auth = env("SMOKE_AUTH", "none");
		String promptText = env("SMOKE_PROMPT", "Reply with exactly one word: pong");
		long totalSec = seconds("SMOKE_TOTAL_TIMEOUT", 300).toSeconds();

		Runtime.getRuntime().addShutdownHook(new Thread(SmokeClient::killAgentTree, "smoke-kill"));
		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(totalSec * 1000);
			}
			catch (InterruptedException e) {
				return;
			}
			System.out.println("STEP watchdog FAIL -> TIMEOUT: the smoke run exceeded " + totalSec + " s");
			System.out.flush();
			killAgentTree();
			Runtime.getRuntime().halt(3);
		}, "smoke-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();

		System.out.println("INFO agent=" + agent + " auth=" + auth + " cmd=" + cmd);
		Path cwd = Files.createTempDirectory("acp-smoke-");

		StdioAcpClientTransport transport = new StdioAcpClientTransport(
				AgentParameters.builder("bash").args("-c", "exec " + cmd).build(), AcpJsonMapper.createDefault());
		transport.setStdErrorHandler(line -> System.out.println("agent| " + line));

		AtomicReference<String> currentSession = new AtomicReference<>();
		AcpAsyncClient client = AcpClient.async(transport)
			.requestTimeout(PROMPT_TIMEOUT)
			.clientCapabilities(new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(false, false), false))
			.clientInfo(new AcpSchema.Implementation("acp-java-smoke", "1"))
			.sessionUpdateHandler(n -> {
				onUpdate(n);
				return Mono.empty();
			})
			// The smoke prompt needs no tools; refuse anything the agent asks for.
			.requestPermissionHandler(req -> {
				System.out.println("  permission request (cancelled): " + req.toolCall());
				return Mono.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionCancelled()));
			})
			.build();

		AtomicReference<AcpSchema.InitializeResponse> init = new AtomicReference<>();
		step("initialize", () -> {
			AcpSchema.InitializeResponse r = client
				.initialize()
				.block(INIT_TIMEOUT);
			if (r == null || r.protocolVersion() == null) {
				throw new IllegalStateException("no protocolVersion in the initialize response");
			}
			init.set(r);
			System.out.println("INFO protocolVersion=" + r.protocolVersion());
			System.out.println("INFO agentInfo=" + r.agentInfo());
			System.out.println("INFO agentCapabilities=" + r.agentCapabilities());
			List<String> ids = new ArrayList<>();
			if (r.authMethods() != null) {
				r.authMethods().forEach(m -> ids.add(m.id()));
			}
			System.out.println("INFO authMethods=" + ids);
			result.put("protocol_version", String.valueOf(r.protocolVersion()));
			result.put("auth_methods", String.valueOf(ids.size()));
			return "protocolVersion=" + r.protocolVersion() + " authMethods=" + ids;
		});

		step("session/new", () -> {
			if (init.get() == null) {
				throw new Skip("initialize failed");
			}
			try {
				AcpSchema.NewSessionResponse r = client
					.newSession(new AcpSchema.NewSessionRequest(cwd.toString(), List.of()))
					.block(NEW_SESSION_TIMEOUT);
				if (r == null || r.sessionId() == null) {
					throw new IllegalStateException("no sessionId");
				}
				currentSession.set(r.sessionId());
				result.put("session_new", "ok");
				return "sessionId=" + r.sessionId() + (r.modes() == null ? "" : " modes=" + modeIds(r.modes()));
			}
			catch (AcpError e) {
				if (e.getCode() != AUTH_REQUIRED) {
					throw e;
				}
				result.put("session_new", "auth_required");
				if (!"none".equals(auth)) {
					throw new IllegalStateException("auth required (-32000) although credentials are present (" + auth
							+ "): " + e.getMessage());
				}
				return "auth required (-32000), as expected without credentials: " + e.getMessage();
			}
		});

		step("prompt", () -> {
			if ("none".equals(auth)) {
				throw new Skip("no credentials (set the agent's API key or log in to its CLI)");
			}
			String sid = currentSession.get();
			if (sid == null) {
				throw new Skip("no session");
			}
			updatesTotal.set(0);
			AcpSchema.PromptResponse r = client
				.prompt(new AcpSchema.PromptRequest(sid, List.of(new AcpSchema.TextContent(promptText))))
				.block(PROMPT_TIMEOUT);
			if (r == null || r.stopReason() == null) {
				throw new IllegalStateException("no stopReason");
			}
			result.put("stop_reason", r.stopReason().value());
			int n = updatesTotal.get();
			String text = agentText.length() > 200 ? agentText.substring(0, 200) + "..." : agentText.toString();
			String detail = "stopReason=" + r.stopReason() + " updates=" + n + " kinds=" + updateKinds + " text="
					+ text.replace('\n', ' ').trim();
			if (n < 1) {
				throw new IllegalStateException("no session/update before the response; " + detail);
			}
			return detail;
		});

		step("close", () -> {
			try {
				client.closeGracefully().block(CLOSE_TIMEOUT);
			}
			finally {
				killAgentTree();
			}
			long left = ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count();
			if (left > 0) {
				throw new IllegalStateException(left + " agent processes still alive after kill");
			}
			return "closed; agent process tree gone";
		});

		StringBuilder sb = new StringBuilder("RESULT pass=" + pass + " fail=" + fail + " skip=" + skip
				+ " updates_total=" + updatesTotal.get());
		result.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
		updateKinds.forEach((k, v) -> sb.append(" upd_").append(k).append('=').append(v));
		System.out.println(sb);
		System.out.flush();
		System.exit(fail == 0 ? 0 : 1);
	}

	static void step(String name, Step s) {
		long t0 = System.nanoTime();
		String ms;
		try {
			String detail = s.run();
			ms = (System.nanoTime() - t0) / 1_000_000 + " ms";
			pass++;
			System.out.println("STEP " + name + " PASS (" + ms + ") -> " + detail);
		}
		catch (Skip e) {
			skip++;
			System.out.println("STEP " + name + " SKIP -> " + e.getMessage());
		}
		catch (Throwable e) {
			ms = (System.nanoTime() - t0) / 1_000_000 + " ms";
			fail++;
			String why = String.valueOf(e);
			if (e instanceof IllegalStateException && String.valueOf(e.getMessage()).startsWith("Timeout")) {
				why = "TIMEOUT " + why;
			}
			System.out.println("STEP " + name + " FAIL (" + ms + ") -> " + why);
		}
		System.out.flush();
	}

	static synchronized void onUpdate(AcpSchema.SessionNotification n) {
		updatesTotal.incrementAndGet();
		String kind = n.update() == null ? "null" : n.update().getClass().getSimpleName();
		updateKinds.merge(kind, 1, Integer::sum);
		if (n.update() instanceof AcpSchema.UsageUpdate u) {
			// Context tokens used and, when the agent reports it, the session cost so far.
			System.out.println("  usage: used=" + u.used() + " size=" + u.size() + " cost=" + u.cost());
			if (u.cost() != null && u.cost().amount() != null) {
				result.put("cost_" + String.valueOf(u.cost().currency()).toLowerCase(), String.valueOf(u.cost().amount()));
			}
		}
		if (n.update() instanceof AcpSchema.AgentMessageChunk c && c.content() instanceof AcpSchema.TextContent t) {
			agentText.append(t.text());
		}
	}

	static String modeIds(AcpSchema.SessionModeState modes) {
		List<String> ids = new ArrayList<>();
		if (modes.availableModes() != null) {
			modes.availableModes().forEach(m -> ids.add(m.id()));
		}
		return ids + " current=" + modes.currentModeId();
	}

	/** Kill every process this JVM started (the agent and its own children), newest first. */
	static void killAgentTree() {
		List<ProcessHandle> tree = new ArrayList<>(ProcessHandle.current().descendants().toList());
		if (tree.isEmpty()) {
			return;
		}
		tree.forEach(ProcessHandle::destroy);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		for (ProcessHandle h : tree) {
			long left = deadline - System.nanoTime();
			try {
				h.onExit().get(Math.max(left, 1), TimeUnit.NANOSECONDS);
			}
			catch (Exception e) {
				h.destroyForcibly();
			}
		}
	}

	static String redact(String s) {
		if (s == null) {
			return null;
		}
		String out = s;
		for (String var : KEY_VARS) {
			String v = System.getenv(var);
			if (v != null && v.length() >= 8) {
				out = out.replace(v, "***");
			}
		}
		return SECRET_SHAPES.matcher(out).replaceAll("***");
	}

	/** Buffers a line, then writes it redacted. */
	static final class RedactingStream extends OutputStream {

		private final OutputStream out;

		private final ByteArrayOutputStream line = new ByteArrayOutputStream();

		RedactingStream(OutputStream out) {
			this.out = out;
		}

		@Override
		public synchronized void write(int b) throws IOException {
			line.write(b);
			if (b == '\n') {
				flushLine();
			}
		}

		@Override
		public synchronized void flush() throws IOException {
			flushLine();
			out.flush();
		}

		private void flushLine() throws IOException {
			if (line.size() > 0) {
				out.write(redact(line.toString(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
				line.reset();
			}
		}

	}

	static String env(String name, String dflt) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? dflt : v;
	}

	static Duration seconds(String name, int dflt) {
		return Duration.ofSeconds(Integer.parseInt(env(name, String.valueOf(dflt))));
	}

	private SmokeClient() {
	}

}
