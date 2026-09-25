package interop;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * Interop client on the Java Streamable HTTP transport. Runs the interop steps against the agent
 * at {@code args[0]} and prints one {@code STEP <name> PASS|FAIL} line per step, then a
 * {@code RESULT} line with pass/fail counts and the number of session updates each step received.
 * The last four steps reconnect on a fresh client and load the first connection's session.
 */
public class JavaClientMain {

	static final Duration T = Duration.ofSeconds(15);

	static final AtomicInteger updates = new AtomicInteger();

	static final Map<String, Integer> updatesPerStep = new LinkedHashMap<>();

	static int pass;

	static int fail;

	interface Step {

		Object run() throws Exception;

	}

	static Object step(String name, Step s) {
		updates.set(0);
		long t0 = System.nanoTime();
		try {
			Object r = s.run();
			pass++;
			System.out.println("STEP " + name + " PASS (" + (System.nanoTime() - t0) / 1_000_000 + " ms) -> " + r);
			return r;
		}
		catch (Throwable e) {
			fail++;
			System.out.println("STEP " + name + " FAIL (" + (System.nanoTime() - t0) / 1_000_000 + " ms) -> " + e);
			return null;
		}
		finally {
			updatesPerStep.put(name, updates.get());
		}
	}

	static AcpAsyncClient newClient(URI uri) {
		return AcpClient.async(new StreamableHttpAcpClientTransport(uri, AcpJsonMapper.createDefault()))
			.requestTimeout(T)
			.sessionUpdateConsumer(n -> {
				updates.incrementAndGet();
				System.out.println("  update: " + n.update());
				return Mono.empty();
			})
			.requestPermissionHandler(req -> {
				System.out.println("  permission request: " + req.options());
				return Mono.just(new AcpSchema.RequestPermissionResponse(
						new AcpSchema.PermissionSelected(req.options().get(0).optionId())));
			})
			.build();
	}

	static AcpSchema.PromptRequest prompt(String sid, String text) {
		return new AcpSchema.PromptRequest(sid, List.of(new AcpSchema.TextContent(text)));
	}

	public static void main(String[] args) {
		URI uri = URI.create(args[0]);
		String cwd = System.getProperty("user.dir");
		AcpAsyncClient client = newClient(uri);
		step("initialize", () -> client.initialize().block(T));
		AcpSchema.NewSessionResponse s = (AcpSchema.NewSessionResponse) step("session/new",
				() -> client.newSession(new AcpSchema.NewSessionRequest(cwd, List.of())).block(T));
		String sid = s == null ? "missing" : s.sessionId();
		step("prompt1", () -> client.prompt(prompt(sid, "hello one")).block(T));
		step("prompt2", () -> client.prompt(prompt(sid, "hello two")).block(T));
		step("prompt3-permission", () -> client.prompt(prompt(sid, "please ask permission")).block(T));
		step("session/load", () -> client.loadSession(new AcpSchema.LoadSessionRequest(sid, cwd, List.of())).block(T));
		step("prompt-after-load", () -> client.prompt(prompt(sid, "after load")).block(T));
		step("close", () -> {
			client.closeGracefully().block(T);
			return "closed";
		});
		AcpAsyncClient c2 = newClient(uri);
		step("reconnect-initialize", () -> c2.initialize().block(T));
		step("reconnect-session/load",
				() -> c2.loadSession(new AcpSchema.LoadSessionRequest(sid, cwd, List.of())).block(T));
		step("reconnect-prompt", () -> c2.prompt(prompt(sid, "after reconnect")).block(T));
		step("reconnect-close", () -> {
			c2.closeGracefully().block(T);
			return "closed";
		});
		printResult();
		System.exit(0);
	}

	static void printResult() {
		StringBuilder sb = new StringBuilder("RESULT pass=" + pass + " fail=" + fail);
		updatesPerStep.forEach((k, v) -> sb.append(' ').append(k.replaceAll("[^A-Za-z0-9]+", "_")).append("_updates=").append(v));
		System.out.println(sb);
	}

}
