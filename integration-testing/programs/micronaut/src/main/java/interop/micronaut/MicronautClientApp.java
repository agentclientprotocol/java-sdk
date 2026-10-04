package interop.micronaut;

import java.util.HashMap;
import java.util.Map;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.integration.AcpClientCustomizer;
import interop.framework.SmokeClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.runtime.Micronaut;
import jakarta.inject.Singleton;

/**
 * The client launcher contract over a Micronaut application: acp-micronaut builds the ACP client
 * bean from {@code acp.client.*} (the transport URI and the capabilities it advertises), the
 * {@link SmokeClient} customizer bean registers its handlers, and the steps in {@code STEPS} run on
 * that bean. The program's {@code @AcpAgent} bean is not served here ({@code acp.agent.enabled=false}).
 */
@Factory
public class MicronautClientApp {

	@Singleton
	SmokeClient smokeClient() {
		return new SmokeClient();
	}

	@Singleton
	AcpClientCustomizer smokeClientHandlers(SmokeClient smokeClient) {
		return smokeClient::customize;
	}

	public static void main(String[] args) throws Exception {
		String[] parsed = SmokeClient.args(args);
		Map<String, Object> properties = new HashMap<>();
		properties.put("acp.agent.enabled", "false");
		properties.put("ws".equals(parsed[0]) ? "acp.client.transport.websocket.uri" : "acp.client.transport.http.uri",
				parsed[1]);
		properties.put("acp.client.capabilities.read-text-file", "true");
		properties.put("acp.client.capabilities.elicitation-form", "true");
		try (ApplicationContext context = Micronaut.build(new String[0]).mainClass(MicronautClientApp.class)
			.banner(false)
			.properties(properties)
			.start()) {
			context.getBean(SmokeClient.class).run(context.getBean(AcpAsyncClient.class), SmokeClient.steps());
		}
		System.exit(0);
	}

}
