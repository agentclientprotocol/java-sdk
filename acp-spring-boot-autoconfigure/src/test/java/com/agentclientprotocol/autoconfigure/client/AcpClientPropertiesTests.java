package com.agentclientprotocol.autoconfigure.client;

import java.net.URI;
import java.time.Duration;

import com.agentclientprotocol.autoconfigure.TransportType;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AcpClientPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	void defaultValues() {
		this.runner.run(context -> {
			AcpClientProperties props = context.getBean(AcpClientProperties.class);
			assertThat(props.getRequestTimeout()).isEqualTo(Duration.ofSeconds(30));
			assertThat(props.getTransport().getType()).isNull();
			assertThat(props.getTransport().getWebsocket().getUri()).isNull();
			assertThat(props.getTransport().getWebsocket().getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
			assertThat(props.getTransport().getStdio().getCommand()).isNull();
			assertThat(props.getTransport().getStdio().getArgs()).isEmpty();
			assertThat(props.getTransport().getStdio().getEnv()).isEmpty();
			assertThat(props.getCapabilities().isReadTextFile()).isTrue();
			assertThat(props.getCapabilities().isWriteTextFile()).isTrue();
			assertThat(props.getCapabilities().isTerminal()).isFalse();
		});
	}

	@Test
	void requestTimeout() {
		this.runner.withPropertyValues("spring.acp.client.request-timeout=2m").run(context -> {
			AcpClientProperties props = context.getBean(AcpClientProperties.class);
			assertThat(props.getRequestTimeout()).isEqualTo(Duration.ofMinutes(2));
		});
	}

	@Test
	void transportTypeWebsocket() {
		this.runner.withPropertyValues("spring.acp.client.transport.type=websocket").run(context -> {
			AcpClientProperties props = context.getBean(AcpClientProperties.class);
			assertThat(props.getTransport().getType()).isEqualTo(TransportType.WEBSOCKET);
		});
	}

	@Test
	void transportTypeStdio() {
		this.runner.withPropertyValues("spring.acp.client.transport.type=stdio").run(context -> {
			AcpClientProperties props = context.getBean(AcpClientProperties.class);
			assertThat(props.getTransport().getType()).isEqualTo(TransportType.STDIO);
		});
	}

	@Test
	void websocketProperties() {
		this.runner
			.withPropertyValues("spring.acp.client.transport.websocket.uri=ws://example.com:9090/acp",
					"spring.acp.client.transport.websocket.connect-timeout=30s")
			.run(context -> {
				AcpClientProperties props = context.getBean(AcpClientProperties.class);
				assertThat(props.getTransport().getWebsocket().getUri())
					.isEqualTo(URI.create("ws://example.com:9090/acp"));
				assertThat(props.getTransport().getWebsocket().getConnectTimeout()).isEqualTo(Duration.ofSeconds(30));
			});
	}

	@Test
	void stdioProperties() {
		this.runner
			.withPropertyValues("spring.acp.client.transport.stdio.command=python",
					"spring.acp.client.transport.stdio.args=agent.py,--verbose",
					"spring.acp.client.transport.stdio.env.PYTHONPATH=/opt/lib")
			.run(context -> {
				AcpClientProperties props = context.getBean(AcpClientProperties.class);
				assertThat(props.getTransport().getStdio().getCommand()).isEqualTo("python");
				assertThat(props.getTransport().getStdio().getArgs()).containsExactly("agent.py", "--verbose");
				assertThat(props.getTransport().getStdio().getEnv()).containsEntry("PYTHONPATH", "/opt/lib");
			});
	}

	@Test
	void capabilitiesProperties() {
		this.runner.withPropertyValues("spring.acp.client.capabilities.read-text-file=false",
				"spring.acp.client.capabilities.write-text-file=false", "spring.acp.client.capabilities.terminal=true")
			.run(context -> {
				AcpClientProperties props = context.getBean(AcpClientProperties.class);
				assertThat(props.getCapabilities().isReadTextFile()).isFalse();
				assertThat(props.getCapabilities().isWriteTextFile()).isFalse();
				assertThat(props.getCapabilities().isTerminal()).isTrue();
			});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AcpClientProperties.class)
	static class PropertiesConfiguration {

	}

}
