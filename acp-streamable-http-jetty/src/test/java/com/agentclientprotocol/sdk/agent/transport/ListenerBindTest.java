/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The listener binds the loopback interface unless told otherwise: an agent started with the
 * defaults is not reachable from the network. Before, the connector set a port and no host, so
 * Jetty bound every interface.
 */
class ListenerBindTest {

	static final AcpAgentFactory AGENTS = AcpAgentFactory.sync(transport -> AcpAgent.sync(transport)
		.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
		.promptHandler((request, context) -> AcpSchema.PromptResponse.endTurn())
		.build());

	@Test
	void theDefaultListenerIsNotReachableOnANonLoopbackAddress() throws Exception {
		Optional<InetAddress> external = nonLoopbackAddress();
		assumeTrue(external.isPresent(), "this machine has no non-loopback address");
		StreamableHttpAcpAgentTransport listener = new StreamableHttpAcpAgentTransport(0, AGENTS);
		listener.start().block(Duration.ofSeconds(10));
		try {
			assertThatThrownBy(() -> connect(external.get(), listener.getPort())).isInstanceOf(IOException.class);
			// Still reachable where it should be.
			connect(InetAddress.getLoopbackAddress(), listener.getPort());
		}
		finally {
			listener.closeGracefully().block(Duration.ofSeconds(10));
		}
	}

	@Test
	void theDefaultListenerAnswersOnLoopbackOverIpv4AndIpv6() throws Exception {
		StreamableHttpAcpAgentTransport listener = new StreamableHttpAcpAgentTransport(0, AGENTS);
		listener.start().block(Duration.ofSeconds(10));
		try {
			assertThat(get(URI.create("http://127.0.0.1:" + listener.getPort() + "/acp"))).isEqualTo(406);
			if (ipv6LoopbackAvailable()) {
				assertThat(get(URI.create("http://[::1]:" + listener.getPort() + "/acp"))).isEqualTo(406);
			}
		}
		finally {
			listener.closeGracefully().block(Duration.ofSeconds(10));
		}
	}

	@Test
	void anExplicitWildcardHostExposesTheListener() throws Exception {
		Optional<InetAddress> external = nonLoopbackAddress();
		assumeTrue(external.isPresent(), "this machine has no non-loopback address");
		StreamableHttpAcpAgentTransport listener = new StreamableHttpAcpAgentTransport(0, "/acp",
				AcpJsonMapper.createDefault(), AGENTS,
				StreamableHttpAcpAgentTransportOptions.builder().host("0.0.0.0").build());
		listener.start().block(Duration.ofSeconds(10));
		try {
			connect(external.get(), listener.getPort());
		}
		finally {
			listener.closeGracefully().block(Duration.ofSeconds(10));
		}
	}

	@Test
	void aNamedHostBindsThatAddressOnly() throws Exception {
		StreamableHttpAcpAgentTransport listener = new StreamableHttpAcpAgentTransport(0, "/acp",
				AcpJsonMapper.createDefault(), AGENTS,
				StreamableHttpAcpAgentTransportOptions.builder().host("127.0.0.1").build());
		listener.start().block(Duration.ofSeconds(10));
		try {
			connect(InetAddress.getByName("127.0.0.1"), listener.getPort());
			if (ipv6LoopbackAvailable()) {
				assertThatThrownBy(() -> connect(InetAddress.getByName("::1"), listener.getPort()))
					.isInstanceOf(IOException.class);
			}
		}
		finally {
			listener.closeGracefully().block(Duration.ofSeconds(10));
		}
	}

	@Test
	void theHostDefaultsToLoopbackAndMustNotBeBlank() {
		assertThat(StreamableHttpAcpAgentTransportOptions.defaults().host()).isNull();
		assertThatThrownBy(() -> StreamableHttpAcpAgentTransportOptions.builder().host(" ").build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	static void connect(InetAddress address, int port) throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(address, port), 2000);
		}
	}

	private static int get(URI uri) throws Exception {
		return HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
					HttpResponse.BodyHandlers.discarding())
			.statusCode();
	}

	static boolean ipv6LoopbackAvailable() {
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("::1"))) {
			return server.getLocalPort() > 0;
		}
		catch (IOException e) {
			return false;
		}
	}

	/** An up, non-loopback address of this machine, IPv4 first; empty when it has none. */
	static Optional<InetAddress> nonLoopbackAddress() throws SocketException {
		InetAddress ipv6 = null;
		for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			if (!nic.isUp() || nic.isLoopback()) {
				continue;
			}
			for (InetAddress address : Collections.list(nic.getInetAddresses())) {
				if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
					continue;
				}
				if (address instanceof Inet4Address) {
					return Optional.of(address);
				}
				if (address instanceof Inet6Address && ipv6 == null) {
					ipv6 = address;
				}
			}
		}
		return Optional.ofNullable(ipv6);
	}

}
