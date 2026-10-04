package interop.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.CloseSession;
import com.agentclientprotocol.sdk.annotation.DeleteSession;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import com.agentclientprotocol.sdk.annotation.ListSessions;
import com.agentclientprotocol.sdk.annotation.LoadSession;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.ResumeSession;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * The handler methods of the framework-hosted smoke agent (integration-testing/smoke.json), shared
 * by every framework program: each framework's program declares a subclass that carries
 * {@code @AcpAgent(name = "interop-<framework>-agent")} and that framework's bean annotation, so the
 * handlers are written once and every framework discovers, derives and serves them its own way.
 *
 * <p>There is deliberately no {@code @Initialize} method: the initialize response, capabilities
 * included, is derived from the annotations. {@code @LoadSession}, {@code @ListSessions},
 * {@code @ResumeSession}, {@code @CloseSession} and {@code @DeleteSession} make it advertise
 * {@code loadSession} and {@code sessionCapabilities.list/resume/close/delete}, which
 * {@code init.agent-capabilities} checks.
 *
 * <p>Prompt directives (steps.json "directives"): plain text is echoed as the chunks
 * {@code "echo: "} and the text; {@code #permission allow}, {@code #elicit form}, {@code #slow},
 * {@code #stop <reason>}, {@code #len <text>} and {@code #big <n>} as the catalogue says; anything
 * else that starts with {@code #} is answered -32602. The extension request {@code _interop/ping}
 * answers {@code {"pong": 1}}. Agent-side assertions go to stderr as {@code STEP agent.<id>} lines.
 *
 * <p>One instance serves every connection, so sessions live in a concurrent map: a session created
 * on one HTTP connection can be loaded on another ({@code http.reconnect}).
 */
public abstract class SmokeAgent {

	private final Map<String, String> sessions = new ConcurrentHashMap<>();

	@NewSession
	public AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest request) {
		String id = "smoke-" + UUID.randomUUID();
		sessions.put(id, Objects.requireNonNullElse(request.cwd(), ""));
		return new AcpSchema.NewSessionResponse(id, null, null, null);
	}

	@LoadSession
	public AcpSchema.LoadSessionResponse loadSession(AcpSchema.LoadSessionRequest request) {
		known(request.sessionId());
		return new AcpSchema.LoadSessionResponse(null, null, null);
	}

	@ResumeSession
	public AcpSchema.ResumeSessionResponse resumeSession(AcpSchema.ResumeSessionRequest request) {
		known(request.sessionId());
		return new AcpSchema.ResumeSessionResponse(null, null, null);
	}

	@ListSessions
	public AcpSchema.ListSessionsResponse listSessions(AcpSchema.ListSessionsRequest request) {
		List<AcpSchema.SessionInfo> out = new ArrayList<>();
		sessions.forEach((id, cwd) -> {
			if (request.cwd() == null || request.cwd().equals(cwd)) {
				out.add(new AcpSchema.SessionInfo(id, cwd, null, null, null, null));
			}
		});
		return new AcpSchema.ListSessionsResponse(out, null, null);
	}

	@CloseSession
	public AcpSchema.CloseSessionResponse closeSession(AcpSchema.CloseSessionRequest request) {
		known(request.sessionId());
		return new AcpSchema.CloseSessionResponse(null);
	}

	@DeleteSession
	public AcpSchema.DeleteSessionResponse deleteSession(AcpSchema.DeleteSessionRequest request) {
		sessions.remove(request.sessionId());
		return new AcpSchema.DeleteSessionResponse(null);
	}

	@ExtRequest("_interop/ping")
	public Map<String, Object> ping(Map<String, Object> params) {
		return Map.of("pong", 1);
	}

	@Prompt
	public AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context,
			NegotiatedCapabilities client) {
		String text = text(request);
		if (!text.startsWith("#")) {
			context.sendMessage("echo: ");
			context.sendMessage(text);
			return AcpSchema.PromptResponse.endTurn();
		}
		int space = text.indexOf(' ');
		String name = space < 0 ? text : text.substring(0, space);
		String args = space < 0 ? "" : text.substring(space + 1);
		return switch (name) {
			case "#permission" -> "allow".equals(args) ? permission(context) : unknown(text);
			case "#elicit" -> "form".equals(args) ? elicitForm(context, client) : unknown(text);
			case "#slow" -> args.isEmpty() ? slow(context) : unknown(text);
			case "#stop" -> {
				context.sendMessage("stop");
				yield new AcpSchema.PromptResponse(AcpSchema.StopReason.of(args));
			}
			case "#len" -> {
				context.sendMessage("len=" + args.length());
				yield AcpSchema.PromptResponse.endTurn();
			}
			case "#big" -> {
				context.sendMessage("x".repeat(Integer.parseInt(args.strip())));
				yield AcpSchema.PromptResponse.endTurn();
			}
			default -> unknown(text);
		};
	}

	/** {@code #permission allow}: perm.selected. */
	private static AcpSchema.PromptResponse permission(SyncPromptContext context) {
		long t0 = System.nanoTime();
		AcpSchema.RequestPermissionResponse response = context.requestPermission(new AcpSchema.RequestPermissionRequest(
				context.getSessionId(),
				new AcpSchema.ToolCallUpdate("perm-1", "interop permission", AcpSchema.ToolKind.EDIT,
						AcpSchema.ToolCallStatus.PENDING),
				List.of(new AcpSchema.PermissionOption("allow", "Allow", AcpSchema.PermissionOptionKind.ALLOW_ONCE),
						new AcpSchema.PermissionOption("reject", "Reject", AcpSchema.PermissionOptionKind.REJECT_ONCE)),
				null));
		String said = response.outcome() instanceof AcpSchema.PermissionSelected selected
				? "selected " + selected.optionId() : "cancelled";
		step("perm.selected", said.equals("selected allow"), t0, "outcome " + said);
		context.sendMessage("permission: " + said);
		return said.equals("cancelled") ? AcpSchema.PromptResponse.cancelled() : AcpSchema.PromptResponse.endTurn();
	}

	/** {@code #elicit form}: elicit.form, only when the client advertised form elicitation. */
	private static AcpSchema.PromptResponse elicitForm(SyncPromptContext context, NegotiatedCapabilities client) {
		long t0 = System.nanoTime();
		if (!client.supportsElicitationForm()) {
			step("elicit.form", false, t0, "the client did not advertise elicitation.form");
			context.sendMessage("elicit error capability");
			return AcpSchema.PromptResponse.endTurn();
		}
		Map<String, AcpSchema.ElicitationPropertySchema> properties = Map.of("name",
				new AcpSchema.StringPropertySchema("string", null, null, null, null, null, null, null, null, null,
						null));
		AcpSchema.CreateElicitationResponse response = context.createElicitation(AcpSchema.CreateElicitationRequest
			.form(context.getSessionId(), "interop form", new AcpSchema.ElicitationSchema(properties, List.of("name"))));
		String action = String.valueOf(response.action());
		Object name = response.content() == null ? null : response.content().get("name");
		step("elicit.form", "accept".equals(action) && "interop".equals(name), t0,
				"action " + action + " content " + response.content());
		context.sendMessage("elicit: " + action + " " + json(response.content()));
		return AcpSchema.PromptResponse.endTurn();
	}

	/**
	 * {@code #slow}: the chunk "tick" every 100 ms; on session/cancel it answers cancelled at once,
	 * uncancelled it answers end_turn after 10 s.
	 */
	private static AcpSchema.PromptResponse slow(SyncPromptContext context) {
		long t0 = System.nanoTime();
		while (System.nanoTime() - t0 < 10_000_000_000L) {
			if (context.isCancelled()) {
				step("cancel.prompt", true, t0, "session/cancel arrived for the running session");
				return AcpSchema.PromptResponse.cancelled();
			}
			context.sendMessage("tick");
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				step("cancel.prompt", context.isCancelled(), t0, "interrupted; cancelled=" + context.isCancelled());
				return AcpSchema.PromptResponse.cancelled();
			}
		}
		return AcpSchema.PromptResponse.endTurn();
	}

	private static AcpSchema.PromptResponse unknown(String text) {
		int space = text.indexOf(' ');
		throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS,
				"unknown directive: " + (space < 0 ? text : text.substring(0, space)));
	}

	private void known(String sessionId) {
		if (!sessions.containsKey(sessionId)) {
			throw new AcpProtocolException(AcpErrorCodes.RESOURCE_NOT_FOUND, "unknown session " + sessionId);
		}
	}

	private static String text(AcpSchema.PromptRequest request) {
		for (AcpSchema.ContentBlock block : request.prompt()) {
			if (block instanceof AcpSchema.TextContent content) {
				return content.text();
			}
		}
		return "";
	}

	/** The JSON of a flat map of strings, numbers and booleans (the elicitation answer). */
	static String json(Map<String, Object> map) {
		if (map == null) {
			return "null";
		}
		StringBuilder out = new StringBuilder("{");
		map.forEach((k, v) -> {
			if (out.length() > 1) {
				out.append(',');
			}
			out.append('"').append(k).append("\":");
			out.append(v instanceof String s ? "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : v);
		});
		return out.append('}').toString();
	}

	/** An agent-side assertion, {@code STEP agent.<id> PASS|FAIL (<ms> ms) -> <detail>}, on stderr. */
	static void step(String id, boolean pass, long t0, String detail) {
		System.err.println("STEP agent." + id + (pass ? " PASS" : " FAIL") + " (" + (System.nanoTime() - t0) / 1_000_000
				+ " ms) -> " + detail.replace('\n', ' ').replace('\r', ' '));
		System.err.flush();
	}

}
