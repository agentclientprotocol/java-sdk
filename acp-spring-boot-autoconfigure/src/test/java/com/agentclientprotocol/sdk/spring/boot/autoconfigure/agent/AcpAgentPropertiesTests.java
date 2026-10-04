package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.time.Duration;

import com.agentclientprotocol.sdk.integration.AcpAgentSettings;
import com.agentclientprotocol.sdk.integration.AcpTransportType;
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
			// Unset: the SDK default
			assertThat(props.getRequestTimeout()).isNull();
			assertThat(props.getTransport().getType()).isNull();
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
			assertThat(props.getTransport().getType()).isEqualTo(AcpTransportType.STDIO);
		});
	}

	@Test
	void everyPropertyBindsOntoTheSettings() {
		this.runner
			.withPropertyValues("spring.acp.agent.cancel-grace-period=3s", "spring.acp.agent.max-prompt-duration=2m",
					"spring.acp.agent.transport.type=websocket", "spring.acp.agent.transport.http.path=/agents/acp",
					"spring.acp.agent.transport.http.listener.port=9123",
					"spring.acp.agent.transport.http.listener.max-concurrent-streams-per-connection=15")
			.run(context -> {
				AcpAgentSettings settings = context.getBean(AcpAgentProperties.class).toSettings();
				assertThat(settings.requestTimeout()).isNull();
				assertThat(settings.cancelGracePeriod()).isEqualTo(Duration.ofSeconds(3));
				assertThat(settings.maxPromptDuration()).isEqualTo(Duration.ofMinutes(2));
				assertThat(settings.transport()).isEqualTo(AcpTransportType.WEBSOCKET);
				assertThat(settings.http().path()).isEqualTo("/agents/acp");
				assertThat(settings.http().listener().port()).isEqualTo(9123);
				assertThat(settings.http().listener().maxConcurrentStreamsPerConnection()).isEqualTo(15);
			});
	}

	@Test
	void anUnsetTypeIsStdio() {
		this.runner.run(context -> assertThat(context.getBean(AcpAgentProperties.class).toSettings().transport())
			.isEqualTo(AcpTransportType.STDIO));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AcpAgentProperties.class)
	static class PropertiesConfiguration {

	}

}
