/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listener's graceful shutdown and the WebSocket close handshake: a client receives the
 * going-away close (1001) the shutdown sends, also when the frame has data to wait behind, and a
 * client that never answers the close does not hold the shutdown past its timeout.
 */
class StreamableHttpAcpAgentTransportWebSocketShutdownTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/** More than the loopback socket buffers hold, with the client's receive buffer kept small. */
	private static final int LARGE_MESSAGE_CHARS = 16 * 1024 * 1024;

	private static final int GOING_AWAY = 1001;

	/**
	 * The close frame waits behind a large frame the client has not read yet: the listener must
	 * not stop Jetty, which drops the connection without a close frame (the client sees 1006),
	 * before the close has gone out.
	 */
	@Test
	void aClientThatIsBehindOnReadingStillReceivesGoingAway() throws Exception {
		StreamableHttpAcpAgentTransport listener = start(Duration.ofSeconds(5));
		try (RawClient client = RawClient.connect(listener.getPort())) {
			client.awaitLargeMessageInFlight();

			CompletableFuture<Void> shutdown = listener.closeGracefully().toFuture();
			// The client catches up only after the shutdown began.
			Thread.sleep(500);
			int closeCode = client.readUntilClose(true);

			assertThat(closeCode).isEqualTo(GOING_AWAY);
			shutdown.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		finally {
			listener.closeGracefully().block(TIMEOUT);
		}
	}

	/** Waiting for the close handshake is bounded by the shutdown timeout. */
	@Test
	void aClientThatNeverAnswersTheCloseDoesNotHoldTheShutdownPastItsTimeout() throws Exception {
		Duration shutdownTimeout = Duration.ofSeconds(1);
		StreamableHttpAcpAgentTransport listener = start(shutdownTimeout);
		try (RawClient client = RawClient.connect(listener.getPort())) {
			client.awaitLargeMessageInFlight();

			long started = System.nanoTime();
			listener.closeGracefully().block(TIMEOUT);
			Duration took = Duration.ofNanos(System.nanoTime() - started);

			assertThat(took).isLessThan(shutdownTimeout.plusSeconds(2));
		}
		finally {
			listener.closeGracefully().block(TIMEOUT);
		}
	}

	private static StreamableHttpAcpAgentTransport start(Duration shutdownTimeout) {
		AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();
		String large = "x".repeat(LARGE_MESSAGE_CHARS);
		// The agent sends a large message as soon as the socket opens, before the client sends
		// anything.
		AcpAgentFactory agentFactory = transport -> {
			transport.sendMessage(new AcpSchema.JSONRPCNotification("_test/large", Map.of("data", large)))
				.subscribe(ignored -> {
				}, error -> {
				});
			return AcpAgent.async(transport)
				.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
				.build();
		};
		StreamableHttpAcpAgentTransport listener = new StreamableHttpAcpAgentTransport(0, "/acp", jsonMapper,
				agentFactory, StreamableHttpAcpAgentTransportOptions.builder().shutdownTimeout(shutdownTimeout).build());
		listener.start().block(TIMEOUT);
		return listener;
	}

	/** A WebSocket client on a plain socket, so that when it reads is the test's choice. */
	private static final class RawClient implements AutoCloseable {

		private final Socket socket;

		private final DataInputStream in;

		private final OutputStream out;

		/** A byte read ahead, or -1. */
		private int pendingFirstByte = -1;

		private RawClient(Socket socket) throws IOException {
			this.socket = socket;
			this.in = new DataInputStream(socket.getInputStream());
			this.out = socket.getOutputStream();
		}

		static RawClient connect(int port) throws IOException {
			Socket socket = new Socket();
			// Small, so the agent's large message fills the connection and stays in flight.
			socket.setReceiveBufferSize(16 * 1024);
			socket.connect(new InetSocketAddress("127.0.0.1", port), (int) TIMEOUT.toMillis());
			socket.setSoTimeout((int) TIMEOUT.toMillis());
			RawClient client = new RawClient(socket);
			client.handshake(port);
			return client;
		}

		private void handshake(int port) throws IOException {
			String key = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
			String request = "GET /acp HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nUpgrade: websocket\r\n"
					+ "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
			out.write(request.getBytes(StandardCharsets.US_ASCII));
			out.flush();
			StringBuilder headers = new StringBuilder();
			while (!headers.toString().endsWith("\r\n\r\n")) {
				int b = in.read();
				if (b < 0) {
					throw new EOFException("Handshake ended early: " + headers);
				}
				headers.append((char) b);
			}
			assertThat(headers.toString()).startsWith("HTTP/1.1 101");
		}

		/** Waits until the first bytes of the large message arrive, so its write is under way. */
		void awaitLargeMessageInFlight() throws IOException {
			int first = in.read();
			assertThat(first).as("first byte of the agent's message").isGreaterThanOrEqualTo(0);
			pendingFirstByte = first;
		}

		/**
		 * Reads frames, skipping data, until a close frame or the end of the stream.
		 * @param answer whether to answer the close, completing the handshake
		 * @return the close frame's code, or 1006 when the stream ended without one
		 */
		int readUntilClose(boolean answer) throws IOException {
			try {
				while (true) {
					int b0 = pendingFirstByte >= 0 ? pendingFirstByte : in.readUnsignedByte();
					pendingFirstByte = -1;
					int b1 = in.readUnsignedByte();
					long length = b1 & 0x7F;
					if (length == 126) {
						length = in.readUnsignedShort();
					}
					else if (length == 127) {
						length = in.readLong();
					}
					int opcode = b0 & 0x0F;
					if (opcode == 0x8) {
						int code = length >= 2 ? in.readUnsignedShort() : 1005;
						in.skipNBytes(Math.max(0, length - 2));
						if (answer) {
							sendClose(code);
						}
						return code;
					}
					in.skipNBytes(length);
				}
			}
			catch (EOFException | java.net.SocketException e) {
				return 1006;
			}
		}

		private void sendClose(int code) throws IOException {
			byte[] mask = { 1, 2, 3, 4 };
			byte[] payload = { (byte) (code >> 8), (byte) code };
			byte[] frame = { (byte) 0x88, (byte) (0x80 | payload.length), mask[0], mask[1], mask[2], mask[3],
					(byte) (payload[0] ^ mask[0]), (byte) (payload[1] ^ mask[1]) };
			out.write(frame);
			out.flush();
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}

	}

}
