/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.agent;

import java.time.Duration;
import java.util.OptionalInt;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import reactor.core.publisher.Mono;

/**
 * The SDK's Streamable HTTP listener (Jetty, its own port; HTTP/1.1, h2c and WebSocket
 * upgrades on one path), one agent per connection. The only class here that touches
 * {@code acp-streamable-http-jetty}, which is optional: {@link AcpAgentRuntime} loads it only
 * when that module is present.
 */
final class HttpListenerHost implements AgentHost {

	/** How long starting the listener may take. */
	private static final Duration START_TIMEOUT = Duration.ofSeconds(30);

	private final StreamableHttpAcpAgentTransport listener;

	HttpListenerHost(AcpAgentFactory agents, AcpAgentConfiguration.Transport.Http http) {
		this.listener = new StreamableHttpAcpAgentTransport(http.getPort(), http.getPath(),
				AcpJsonMapper.createDefault(), agents, options(http));
	}

	static StreamableHttpAcpAgentTransportOptions options(AcpAgentConfiguration.Transport.Http http) {
		var options = StreamableHttpAcpAgentTransportOptions.builder();
		Long maxPostBodySize = http.getMaxPostBodySize();
		if (maxPostBodySize != null) {
			options.maxPostBodyBytes(maxPostBodySize);
		}
		Duration keepAliveInterval = http.getKeepAliveInterval();
		if (keepAliveInterval != null) {
			options.keepAliveInterval(keepAliveInterval);
		}
		Integer mailboxCapacity = http.getMailboxCapacity();
		if (mailboxCapacity != null) {
			options.mailboxCapacity(mailboxCapacity);
		}
		Integer maxPendingSseEvents = http.getMaxPendingSseEvents();
		if (maxPendingSseEvents != null) {
			options.maxPendingSseEvents(maxPendingSseEvents);
		}
		Integer maxWebSocketPendingFrames = http.getMaxWebSocketPendingFrames();
		if (maxWebSocketPendingFrames != null) {
			options.maxWebSocketPendingFrames(maxWebSocketPendingFrames);
		}
		Integer maxProvisionalSessions = http.getMaxProvisionalSessions();
		if (maxProvisionalSessions != null) {
			options.maxProvisionalSessions(maxProvisionalSessions);
		}
		Integer maxConcurrentStreams = http.getMaxConcurrentStreamsPerConnection();
		if (maxConcurrentStreams != null) {
			options.maxConcurrentStreamsPerConnection(maxConcurrentStreams);
		}
		Duration shutdownTimeout = http.getShutdownTimeout();
		if (shutdownTimeout != null) {
			options.shutdownTimeout(shutdownTimeout);
		}
		return options.build();
	}

	@Override
	public void start() {
		listener.start().block(START_TIMEOUT);
	}

	@Override
	public Mono<Void> awaitTermination() {
		return listener.awaitTermination();
	}

	@Override
	public Mono<Void> closeGracefully() {
		return listener.closeGracefully();
	}

	@Override
	public OptionalInt port() {
		return OptionalInt.of(listener.getPort());
	}

	@Override
	public boolean endsWithItsClient() {
		// A listener serves many clients and ends only when it is closed.
		return false;
	}

}
