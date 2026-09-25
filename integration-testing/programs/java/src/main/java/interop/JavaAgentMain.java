package interop;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import reactor.core.publisher.Mono;

/**
 * Interop agent served by the Java Streamable HTTP listener. Each prompt streams two updates
 * ("echo: " and the prompt text); a prompt containing "permission" first asks the client for
 * permission. Prints {@code READY <port>} on stdout once listening, and one {@code [http]} line per
 * request (method, path, protocol, status, ACP headers) so scenarios can assert on HTTP versions.
 */
public class JavaAgentMain {

	public static void main(String[] args) throws Exception {
		int port = Integer.parseInt(args.length > 0 ? args[0] : "0");
		AtomicInteger counter = new AtomicInteger();
		AcpAgentFactory factory = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
			.initializeHandler(r -> {
				System.err.println("[agent] initialize " + r);
				return Mono.just(new AcpSchema.InitializeResponse(AcpSchema.LATEST_PROTOCOL_VERSION,
						new AcpSchema.AgentCapabilities(true, null, null), List.of()));
			})
			.newSessionHandler(r -> {
				String id = "java-sess-" + counter.incrementAndGet();
				System.err.println("[agent] session/new " + id);
				return Mono.just(new AcpSchema.NewSessionResponse(id, null, null));
			})
			.loadSessionHandler(r -> {
				System.err.println("[agent] session/load " + r.sessionId());
				return Mono.just(new AcpSchema.LoadSessionResponse(null, null));
			})
			.resumeSessionHandler(r -> {
				System.err.println("[agent] session/resume " + r.sessionId());
				return Mono.just(new AcpSchema.ResumeSessionResponse(null, null));
			})
			.promptHandler((request, context) -> {
				String text = request.text();
				System.err.println("[agent] session/prompt " + text);
				Mono<Void> work = text.contains("permission")
						? context.askPermission("interop permission")
							.doOnNext(b -> System.err.println("[agent] permission granted=" + b))
							.flatMap(b -> context.sendMessage("permission granted=" + b))
						: Mono.empty();
				return work.then(context.sendMessage("echo: "))
					.then(context.sendMessage(text))
					.thenReturn(AcpSchema.PromptResponse.endTurn());
			})
			.build());
		StreamableHttpAcpAgentTransport server = new StreamableHttpAcpAgentTransport(port,
				StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH, AcpJsonMapper.createDefault(), factory);
		server.start().block();
		attachRequestLog(server);
		System.err.println("listening http://127.0.0.1:" + server.getPort() + "/acp");
		System.out.println("READY " + server.getPort());
		server.awaitTermination().block();
	}

	/**
	 * Harness-only instrumentation: the transport has no public request-log hook, so reach its
	 * Jetty server reflectively. If the field is renamed the scenario fails on its missing
	 * {@code [http]} lines rather than silently passing.
	 */
	private static void attachRequestLog(StreamableHttpAcpAgentTransport transport) throws Exception {
		Field f = StreamableHttpAcpAgentTransport.class.getDeclaredField("server");
		f.setAccessible(true);
		Server jetty = (Server) f.get(transport);
		jetty.setRequestLog(new CustomRequestLog((RequestLog.Writer) line -> System.err.println(line),
				"[http] %m %U %H -> %s ct=\"%{Content-Type}i\" accept=\"%{Accept}i\" conn=\"%{Acp-Connection-Id}i\""
						+ " sess=\"%{Acp-Session-Id}i\" upgrade=\"%{Upgrade}i\" respct=\"%{Content-Type}o\""));
	}

}
