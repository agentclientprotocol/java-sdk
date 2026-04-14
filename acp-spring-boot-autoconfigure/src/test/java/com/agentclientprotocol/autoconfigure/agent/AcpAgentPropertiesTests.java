package com.agentclientprotocol.autoconfigure.agent;

import java.time.Duration;

import com.agentclientprotocol.autoconfigure.TransportType;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AcpAgentPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	void defaultValues() {
		this.runner.run(context -> {
			AcpAgentProperties props = context.getBean(AcpAgentProperties.class);
			assertThat(props.isEnabled()).isTrue();
			assertThat(props.getRequestTimeout()).isEqualTo(Duration.ofSeconds(60));
			assertThat(props.getTransport().getType()).isNull();
			assertThat(props.getTransport().getWebsocket().getPort()).isEqualTo(8080);
			assertThat(props.getTransport().getWebsocket().getPath()).isEqualTo("/acp");
		});
	}

	@Test
	void enabled() {
		this.runner.withPropertyValues("spring.acp.agent.enabled=false").run(context -> {
			AcpAgentProperties props = context.getBean(AcpAgentProperties.class);
			assertThat(props.isEnabled()).isFalse();
		});
	}

	@Test
	void requestTimeout() {
		this.runner.withPropertyValues("spring.acp.agent.request-timeout=5m").run(context -> {
			AcpAgentProperties props = context.getBean(AcpAgentProperties.class);
			assertThat(props.getRequestTimeout()).isEqualTo(Duration.ofMinutes(5));
		});
	}

	@Test
	void transportType() {
		this.runner.withPropertyValues("spring.acp.agent.transport.type=stdio").run(context -> {
			AcpAgentProperties props = context.getBean(AcpAgentProperties.class);
			assertThat(props.getTransport().getType()).isEqualTo(TransportType.STDIO);
		});
	}

	@Test
	void websocketProperties() {
		this.runner
			.withPropertyValues("spring.acp.agent.transport.websocket.port=9090",
					"spring.acp.agent.transport.websocket.path=/custom-acp")
			.run(context -> {
				AcpAgentProperties props = context.getBean(AcpAgentProperties.class);
				assertThat(props.getTransport().getWebsocket().getPort()).isEqualTo(9090);
				assertThat(props.getTransport().getWebsocket().getPath()).isEqualTo("/custom-acp");
			});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AcpAgentProperties.class)
	static class PropertiesConfiguration {

	}

}
