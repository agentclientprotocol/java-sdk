package interop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * {@code client.sh --transport stdio --mode raw}: the Java client against the raw conformance
 * driver (programs/raw) playing the agent. The driver puts frames on the wire that no SDK's typed
 * API produces; these steps check that the Java client survives each one and does not hang.
 *
 * <p>
 * Each case is one prompt, {@code #raw <case>}, on a fresh session; the driver answers
 * {@code initialize} and {@code session/new} normally. What the driver sends per case, and what
 * PASS means here:
 * </p>
 * <ul>
 * <li>{@code raw.error-no-message} ({@code #raw error-no-message}): the prompt is answered with an
 * error object that has a {@code code} and no {@code message}. PASS when the prompt fails with an
 * error (not a TIMEOUT) and a following {@code session/new} succeeds.</li>
 * <li>{@code raw.null-id-response} ({@code #raw null-id-response}): a stray response
 * {@code {"jsonrpc":"2.0","id":null,"result":{}}}, then the prompt's own response, end_turn. PASS
 * when the prompt answers end_turn and a following {@code session/new} succeeds.</li>
 * <li>{@code raw.unknown-update} ({@code #raw unknown-update}): a {@code session/update} with
 * {@code sessionUpdate: "interop_future_update"}, then an {@code agent_message_chunk}
 * "after-unknown", then end_turn. PASS when "after-unknown" arrives and the prompt answers
 * end_turn.</li>
 * <li>{@code raw.null-result} ({@code #raw null-result}): the prompt is answered
 * {@code "result": null}, which the schema forbids for a PromptResponse. PASS when the prompt ends
 * (an error or a response, never a TIMEOUT) and a following {@code session/new} succeeds.</li>
 * </ul>
 * {@code init.initialize} and {@code conn.close} are the catalogue's.
 */
final class Raw {

	static final Map<String, Client.Body> STEPS = new LinkedHashMap<>();

	static {
		STEPS.put("init.initialize", Client::initInitialize);
		STEPS.put("raw.error-no-message", () -> {
			Client.Conn c = Client.main();
			String sid = c.newSession();
			try {
				AcpSchema.PromptResponse r = c.prompt(sid, "#raw error-no-message");
				throw new Client.StepFailure("the prompt answered " + r.stopReason() + ", expected an error");
			}
			catch (Client.StepFailure e) {
				throw e;
			}
			catch (RuntimeException e) {
				Client.check(!Client.isTimeout(e), Client.describe(e));
				return "failed with " + Client.describe(e) + "; " + survived(c);
			}
		});
		STEPS.put("raw.null-id-response", () -> {
			Client.Conn c = Client.main();
			String sid = c.newSession();
			AcpSchema.PromptResponse r = c.prompt(sid, "#raw null-id-response");
			Client.check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
			return "end_turn; " + survived(c);
		});
		STEPS.put("raw.unknown-update", () -> {
			Client.Conn c = Client.main();
			String sid = c.newSession();
			AcpSchema.PromptResponse r = c.prompt(sid, "#raw unknown-update");
			Client.check(r.stopReason() == AcpSchema.StopReason.END_TURN, "stopReason " + r.stopReason());
			Client.await(() -> c.chunks(sid).contains("after-unknown"),
					() -> "no chunk \"after-unknown\" in " + c.chunks(sid) + " (P1: the Java client fails the unknown update)");
			return "after-unknown arrived; end_turn";
		});
		STEPS.put("raw.null-result", () -> {
			Client.Conn c = Client.main();
			String sid = c.newSession();
			String outcome;
			try {
				AcpSchema.PromptResponse r = c.prompt(sid, "#raw null-result");
				outcome = "answered " + r;
			}
			catch (RuntimeException e) {
				Client.check(!Client.isTimeout(e), Client.describe(e));
				outcome = "failed with " + Client.describe(e);
			}
			return outcome + "; " + survived(c);
		});
		STEPS.put("conn.close", Client::connClose);
	}

	static String survived(Client.Conn c) {
		String sid = c.client.newSession(new AcpSchema.NewSessionRequest(Client.dir.toString(), List.of()))
			.block(Client.T)
			.sessionId();
		return "session/new after it: " + sid;
	}

	private Raw() {
	}

}
