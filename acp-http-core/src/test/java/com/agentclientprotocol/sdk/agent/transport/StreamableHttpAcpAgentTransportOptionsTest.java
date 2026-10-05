/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Every option the builder sets reaches the options, and every limit is checked. */
class StreamableHttpAcpAgentTransportOptionsTest {

	@Test
	void theBuilderSetsEveryOption() {
		Executor executor = Runnable::run;
		StreamableHttpAcpAgentTransportOptions options = StreamableHttpAcpAgentTransportOptions.builder()
			.maxPostBodyBytes(1)
			.mailboxCapacity(2)
			.maxPendingSseEvents(3)
			.maxWebSocketPendingFrames(4)
			.maxProvisionalSessions(5)
			.keepAliveInterval(Duration.ZERO)
			.maxConcurrentStreamsPerConnection(6)
			.shutdownTimeout(Duration.ofSeconds(7))
			.virtualThreads(true)
			.executor(executor)
			.host("0.0.0.0")
			.allowedOrigins(List.of("https://allowed.example"))
			.build();
		assertThat(options.maxPostBodyBytes()).isEqualTo(1);
		assertThat(options.mailboxCapacity()).isEqualTo(2);
		assertThat(options.maxPendingSseEvents()).isEqualTo(3);
		assertThat(options.maxWebSocketPendingFrames()).isEqualTo(4);
		assertThat(options.maxProvisionalSessions()).isEqualTo(5);
		assertThat(options.keepAliveInterval()).isZero();
		assertThat(options.maxConcurrentStreamsPerConnection()).isEqualTo(6);
		assertThat(options.shutdownTimeout()).isEqualTo(Duration.ofSeconds(7));
		assertThat(options.executor()).isSameAs(executor);
		assertThat(options.virtualThreads()).isTrue();
		assertThat(options.host()).isEqualTo("0.0.0.0");
		assertThat(options.isOriginAllowed("https://allowed.example")).isTrue();
	}

	@Test
	void everyLimitIsChecked() {
		List<UnaryOperator<StreamableHttpAcpAgentTransportOptions.Builder>> invalid = List.of(
				builder -> builder.maxPostBodyBytes(0), builder -> builder.mailboxCapacity(0),
				builder -> builder.maxPendingSseEvents(0), builder -> builder.maxWebSocketPendingFrames(0),
				builder -> builder.maxProvisionalSessions(0), builder -> builder.keepAliveInterval(Duration.ofSeconds(-1)),
				builder -> builder.maxConcurrentStreamsPerConnection(0),
				builder -> builder.shutdownTimeout(Duration.ZERO),
				builder -> builder.shutdownTimeout(Duration.ofSeconds(-1)),
				builder -> builder.executor(Runnable::run).virtualThreads(false), builder -> builder.host(" "));
		for (UnaryOperator<StreamableHttpAcpAgentTransportOptions.Builder> change : invalid) {
			assertThatIllegalArgumentException()
				.isThrownBy(() -> change.apply(StreamableHttpAcpAgentTransportOptions.builder()).build());
		}
	}

}
