package com.agentclientprotocol.autoconfigure.agent;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.agentclientprotocol.autoconfigure.agent.AcpAgentHttpAutoConfigurationTests.EchoAgentConfiguration;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application shuts down promptly while a client still holds a connection open. The
 * servlet left to its own {@code destroy()} waited out a 30 second timeout.
 */
class AcpAgentHttpShutdownTests {

	private static final Duration PROMPT = Duration.ofSeconds(10);

	@Test
	void servletWebApplicationShutsDownPromptlyWithAnOpenConnection() {
		assertShutsDownPromptly("servlet");
	}

	@Test
	void listenerApplicationShutsDownPromptlyWithAnOpenConnection() {
		assertShutsDownPromptly("none");
	}

	private void assertShutsDownPromptly(String webApplicationType) {
		ConfigurableApplicationContext context = SpringApplication.run(TestApplication.class, "--server.port=0",
				"--spring.main.web-application-type=" + webApplicationType, "--spring.acp.agent.transport.type=http",
				"--spring.acp.agent.transport.http.port=0");
		int port = (context instanceof WebServerApplicationContext web) ? web.getWebServer().getPort()
				: context.getBean(StreamableHttpAcpAgentTransport.class).getPort();
		AcpSyncClient client = AcpClient
			.sync(new StreamableHttpAcpClientTransport(URI.create("http://localhost:" + port + "/acp"),
					AcpJsonMapper.createDefault()))
			.requestTimeout(PROMPT)
			.build();
		try {
			client.initialize();
			client.newSession(new NewSessionRequest("/workspace", List.of()));

			long start = System.nanoTime();
			context.close();
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(PROMPT);
		}
		finally {
			context.close();
			client.close();
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(EchoAgentConfiguration.class)
	static class TestApplication {

	}

}
