package load;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * Load-test agent: every prompt streams two updates, then ends the turn after a short think.
 * Usage: {@code LoadServer <port> [thinkMs] [maxConcurrentStreamsPerConnection]}. Prints {@code READY <port>} once listening; the
 * runner samples this JVM (heap, threads) with jcmd.
 */
public class LoadServer {

	public static void main(String[] args) throws Exception {
		int port = Integer.parseInt(args[0]);
		long thinkMs = Long.parseLong(args.length > 1 ? args[1] : "50");
		AtomicInteger sessions = new AtomicInteger();
		AcpAgentFactory factory = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
			.initializeHandler(r -> Mono.just(new AcpSchema.InitializeResponse(AcpSchema.LATEST_PROTOCOL_VERSION,
					new AcpSchema.AgentCapabilities(true, null, null), List.of())))
			.newSessionHandler(r -> Mono.just(new AcpSchema.NewSessionResponse("s-" + sessions.incrementAndGet(), null, null)))
			.promptHandler((r, ctx) -> ctx.sendMessage("one")
				.then(ctx.sendMessage("two"))
				.then(Mono.delay(Duration.ofMillis(thinkMs)))
				.thenReturn(AcpSchema.PromptResponse.endTurn()))
			.build());
		StreamableHttpAcpAgentTransportOptions.Builder options = StreamableHttpAcpAgentTransportOptions.builder();
		if (args.length > 2) {
			options.maxConcurrentStreamsPerConnection(Integer.parseInt(args[2]));
		}
		StreamableHttpAcpAgentTransport server = new StreamableHttpAcpAgentTransport(port,
				StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH, AcpJsonMapper.createDefault(), factory, options.build());
		server.start().block(Duration.ofSeconds(30));
		System.out.println("READY " + server.getPort());
		Thread.currentThread().join();
	}

}
