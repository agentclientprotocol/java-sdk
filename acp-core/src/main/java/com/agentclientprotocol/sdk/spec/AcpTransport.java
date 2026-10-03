/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.List;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Defines the asynchronous transport layer for the Agent Client Protocol (ACP).
 *
 * <p>
 * The AcpTransport interface provides the foundation for implementing custom transport
 * mechanisms in the Agent Client Protocol. It handles the bidirectional communication
 * between the client and agent components, supporting asynchronous message exchange using
 * JSON-RPC format.
 * </p>
 *
 * <p>
 * Implementations of this interface are responsible for:
 * </p>
 * <ul>
 * <li>Managing the lifecycle of the transport connection</li>
 * <li>Handling incoming messages and errors from the peer</li>
 * <li>Sending outbound messages to the peer</li>
 * </ul>
 *
 * <p>
 * The transport layer is designed to be protocol-agnostic, allowing for various
 * implementations such as STDIO, HTTP, SSE, or custom protocols.
 * </p>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
public interface AcpTransport {

	/**
	 * Closes the transport connection and releases any associated resources.
	 *
	 * <p>
	 * This method ensures proper cleanup of resources when the transport is no longer
	 * needed. It should handle the graceful shutdown of any active connections.
	 * </p>
	 */
	default void close() {
		this.closeGracefully()
			.subscribe(ignored -> {
			}, error -> LoggerFactory.getLogger(AcpTransport.class)
				.warn("Closing the transport failed: {}", error.toString(), error));
	}

	/**
	 * Closes the transport connection and releases any associated resources
	 * asynchronously.
	 * @return a {@link Mono<Void>} that completes when the connection has been closed.
	 */
	Mono<Void> closeGracefully();

	/**
	 * Sends a message to the peer asynchronously.
	 *
	 * <p>
	 * This method handles the transmission of messages to the peer in an asynchronous
	 * manner. Messages are sent in JSON-RPC format as specified by the ACP protocol.
	 * </p>
	 * @param message the {@link JSONRPCMessage} to be sent to the peer
	 * @return a {@link Mono<Void>} that completes when the message has been sent
	 */
	Mono<Void> sendMessage(JSONRPCMessage message);

	/**
	 * Unmarshals the given data into an object of the specified type.
	 * @param <T> the type of the object to unmarshal
	 * @param data the data to unmarshal
	 * @param typeRef the type reference for the object to unmarshal
	 * @return the unmarshalled object
	 */
	<T> T unmarshalFrom(Object data, TypeRef<T> typeRef);

	/**
	 * Reads the params of an inbound request or notification as the method's type. Params
	 * the type cannot be read from (a value of the wrong JSON type, a required field
	 * missing) are a JSON-RPC 2.0 -32602 Invalid params error (section 5.1), not an
	 * internal error.
	 * @param <T> the params type
	 * @param params the params as received
	 * @param typeRef the method's params type
	 * @return the params as that type
	 * @throws AcpProtocolException with code -32602 when they cannot be read as it
	 */
	default <T> T unmarshalParams(Object params, TypeRef<T> typeRef) {
		T value;
		try {
			value = unmarshalFrom(params, typeRef);
		}
		catch (IllegalArgumentException e) {
			throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "Invalid params", firstLine(e.getMessage()));
		}
		String missing = RequiredFields.firstMissing(value);
		if (missing != null) {
			throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "Invalid params",
					"missing required field: " + missing);
		}
		return value;
	}

	private static @Nullable String firstLine(@Nullable String message) {
		if (message == null) {
			return null;
		}
		int end = message.indexOf('\n');
		return end < 0 ? message : message.substring(0, end);
	}

	/**
	 * Returns the list of protocol versions supported by this transport.
	 * @return list of supported protocol versions
	 */
	default List<Integer> protocolVersions() {
		return List.of(AcpSchema.LATEST_PROTOCOL_VERSION);
	}

}
