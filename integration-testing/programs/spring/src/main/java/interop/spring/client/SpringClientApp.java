package interop.spring.client;

import java.util.HashMap;
import java.util.Map;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.integration.AcpClientCustomizer;
import interop.framework.SmokeClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * The client launcher contract over a Spring Boot 4 application: acp-spring-boot-starter builds the
 * ACP client bean from {@code spring.acp.client.*} (the transport URI and the capabilities it
 * advertises), the {@link SmokeClient} customizer bean registers its handlers, and the steps in
 * {@code STEPS} run on that bean. The context is closed once the RESULT line is printed.
 */
@SpringBootApplication
public class SpringClientApp {

	@Bean
	SmokeClient smokeClient() {
		return new SmokeClient();
	}

	@Bean
	AcpClientCustomizer smokeClientHandlers(SmokeClient smokeClient) {
		return smokeClient::customize;
	}

	public static void main(String[] args) throws Exception {
		String[] parsed = SmokeClient.args(args);
		Map<String, Object> properties = new HashMap<>();
		properties.put("spring.main.banner-mode", "off");
		properties.put("spring.main.web-application-type", "none");
		properties.put("ws".equals(parsed[0]) ? "spring.acp.client.transport.websocket.uri"
				: "spring.acp.client.transport.http.uri", parsed[1]);
		properties.put("spring.acp.client.capabilities.read-text-file", "true");
		properties.put("spring.acp.client.capabilities.elicitation-form", "true");
		SpringApplication application = new SpringApplication(SpringClientApp.class);
		application.setDefaultProperties(properties);
		application.setLogStartupInfo(false);
		try (ConfigurableApplicationContext context = application.run()) {
			context.getBean(SmokeClient.class).run(context.getBean(AcpAsyncClient.class), SmokeClient.steps());
		}
		System.exit(0);
	}

}
