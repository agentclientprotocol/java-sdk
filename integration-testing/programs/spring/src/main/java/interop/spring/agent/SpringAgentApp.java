package interop.spring.agent;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The launcher contract (integration-testing/README.md, "Contracts") over a Spring Boot 4
 * application whose {@code @AcpAgent} bean ({@link SpringSmokeAgent}) acp-spring-boot-starter
 * serves: {@code --transport stdio}, or {@code --transport http --port <port>}, which runs a
 * servlet web application on {@code server.port} with the SDK's Streamable HTTP servlet mounted at
 * {@code /acp}, and prints {@code READY <port>} once it accepts connections. This class only maps
 * the flags onto Spring properties. Over stdio the application is not a web application, is kept
 * alive by {@code spring.main.keep-alive} and exits when its input ends
 * ({@code spring.acp.agent.shutdown-on-transport-end}); over HTTP it runs until SIGTERM.
 *
 * <p>{@code --transport ws} is refused: the servlet takes no WebSocket upgrades on
 * {@code server.port} yet (TODO: the WebSocket cell, smoke.json "pendingAgentTransports").
 */
@SpringBootApplication
public class SpringAgentApp {

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
			usage("unsupported transport " + transport + " (the Spring agent serves stdio, http and ws)");
		}
		Map<String, Object> properties = new HashMap<>();
		properties.put("spring.main.banner-mode", "off");
		if (stdio) {
			properties.put("spring.main.web-application-type", "none");
			properties.put("spring.main.keep-alive", "true");
			properties.put("spring.acp.agent.transport.type", "stdio");
		}
		else {
			properties.put("spring.main.web-application-type", "servlet");
			properties.put("server.address", "127.0.0.1");
			properties.put("server.port", port);
			// http and ws are one endpoint: the servlet takes the WebSocket upgrade on its path.
			properties.put("spring.acp.agent.transport.type", "ws".equals(transport) ? "websocket" : "http");
			properties.put("spring.acp.agent.transport.http.path", "/acp");
		}
		SpringApplication application = new SpringApplication(SpringAgentApp.class);
		application.setDefaultProperties(properties);
		application.setLogStartupInfo(false);
		ConfigurableApplicationContext context = application.run();
		if (!stdio) {
			System.out.println("READY " + context.getEnvironment().getProperty("local.server.port"));
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
