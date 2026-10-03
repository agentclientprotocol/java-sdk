package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.net.URI;

import com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent.AcpAgentHttpAutoConfigurationTests.EchoAgentConfiguration;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent served by the application's own embedded Tomcat, through the mounted servlet.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "spring.acp.agent.transport.type=http")
class AcpAgentHttpServletIntegrationTests {

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationContext context;

	@Test
	void agentAnswersOverTheApplicationServer() {
		assertThat(this.context.getBeanNamesForType(StreamableHttpAcpAgentTransport.class)).isEmpty();
		AcpAgentHttpAutoConfigurationTests.assertRoundTrip(new StreamableHttpAcpClientTransport(
				URI.create("http://localhost:" + this.port + "/acp"), AcpJsonMapper.createDefault()));
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(EchoAgentConfiguration.class)
	static class TestApplication {

	}

}
