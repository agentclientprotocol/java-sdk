package interop.micronaut;

import java.util.HashMap;
import java.util.Map;

import com.agentclientprotocol.sdk.micronaut.agent.AcpAgentRuntime;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;

/**
 * The launcher contract (integration-testing/README.md, "Contracts") over a Micronaut application
 * whose {@code @AcpAgent} bean ({@link MicronautSmokeAgent}) acp-micronaut serves:
 * {@code --transport stdio}, or {@code --transport http|ws --port <port>}, which prints
 * {@code READY <port>} once the SDK listener accepts connections. This class only maps the flags
 * onto {@code acp.agent.*} settings. Over
 * stdio the process exits when its input ends; over HTTP it runs until SIGTERM.
 */
public final class MicronautAgentApp {

	private MicronautAgentApp() {
	}

	public static void main(String[] args) {
		String transport = null;
		String port = "0";
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--transport" -> transport = value(args, ++i);
				case "--port" -> port = value(args, ++i);
				default -> usage("unknown flag " + args[i]);
			}
		}
		if (transport == null) {
			usage("--transport is required");
		}
		boolean stdio = "stdio".equals(transport);
		if (!stdio && !"http".equals(transport) && !"ws".equals(transport)) {
			usage("unknown transport " + transport);
		}
		Map<String, Object> properties = new HashMap<>();
		// One listener serves Streamable HTTP and WebSocket upgrades on /acp.
		properties.put("acp.agent.transport.type", stdio ? "stdio" : "http");
		properties.put("acp.agent.transport.http.listener.port", port);
		ApplicationContext context = Micronaut.build(new String[0]).mainClass(MicronautAgentApp.class).banner(false)
			.properties(properties).start();
		if (!stdio) {
			int bound = context.getBean(AcpAgentRuntime.class).port().orElseThrow();
			System.out.println("READY " + bound);
			System.out.flush();
		}
	}

	private static String value(String[] args, int i) {
		if (i >= args.length) {
			usage(args[i - 1] + " needs a value");
		}
		return args[i];
	}

	private static void usage(String problem) {
		System.err.println(problem);
		System.err.println("usage: agent.sh --transport stdio | --transport http|ws --port <port>");
		System.exit(2);
	}

}
