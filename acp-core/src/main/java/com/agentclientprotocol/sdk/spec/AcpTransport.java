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
 * Moves ACP's JSON-RPC messages between this side of a connection and its peer. A transport
 * carries one connection: it writes the messages the SDK sends and hands the SDK every
 * message that arrives. An application chooses an implementation and passes it to a client or
 * agent builder; after that it calls the transport at most to close it or to wait for its end.
 *
 * <p>Two interfaces extend it, one per side: {@link AcpClientTransport} for a client and
 * {@link AcpAgentTransport} for an agent. The protocol session that the builder puts on top of
 * the transport owns everything else JSON-RPC needs: request ids, pending responses, timeouts
 * and handler dispatch.
 *
 * <p>Implementations must accept {@link #sendMessage} from several threads at once: the
 * session answers the peer's requests on the thread that reads them while user threads send
 * their own requests and notifications. {@link #unmarshalFrom} must report a value that does
 * not fit the requested type with an {@link IllegalArgumentException}, which
 * {@link #unmarshalParams} turns into an Invalid params error.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
public interface AcpTransport {

	/**
	 * Closes the transport without reporting the outcome; {@link #closeGracefully()} is the
	 * variant to wait on. The interface's default subscribes to {@link #closeGracefully()}
	 * and returns when that subscription returns: at once when the implementation closes
	 * asynchronously, and only after the close when it does the work on the subscribing
	 * thread. A failed close is logged at WARN by this interface's logger.
	 */
	default void close() {
		this.closeGracefully()
			.subscribe(ignored -> {
			}, error -> LoggerFactory.getLogger(AcpTransport.class)
				.warn("Closing the transport failed: {}", error.toString(), error));
	}

	/**
	 * Closes the transport: stops reading from and writing to the peer, and releases the
	 * threads, streams or network connections it holds. What it waits for differs per
	 * implementation; the stdio client, for example, gives the agent process time to exit.
	 * The shipped transports can be closed more than once, with this method and
	 * {@link #close()} in either order: only the first call closes.
	 * @return a Mono that completes when the transport is closed
	 */
	Mono<Void> closeGracefully();

	/**
	 * Sends one JSON-RPC message, a request, response or notification, to the peer. When the
	 * returned Mono completes depends on the implementation: once the message is queued for
	 * writing (stdio, WebSocket), or once the peer has accepted it (Streamable HTTP, which
	 * posts it and waits for the HTTP answer).
	 * @param message the message to send
	 * @return a Mono that completes when the message has been handed on, or errors when it
	 * cannot be sent
	 */
	Mono<Void> sendMessage(JSONRPCMessage message);

	/**
	 * Converts a value read from JSON, such as a message's params or a response's result, to
	 * the given type with this transport's JSON mapper.
	 * @param <T> the type to convert to
	 * @param data the value as read from JSON, usually a map
	 * @param typeRef the type to convert to
	 * @return the value as that type
	 * @throws IllegalArgumentException if the value does not fit the type
	 */
	<T> T unmarshalFrom(Object data, TypeRef<T> typeRef);

	/**
	 * Reads the params of an inbound request or notification as the method's params type, and
	 * checks that the {@link AcpSchema} records in them carry every field the ACP schema
	 * requires. Params of the wrong shape are the peer's mistake, not an internal error: they
	 * fail with JSON-RPC error -32602 (Invalid params), which the session sends back when they
	 * belong to a request. An application's own params types, such as an extension method's,
	 * are converted but not checked.
	 * @param <T> the params type
	 * @param params the params as received
	 * @param typeRef the method's params type
	 * @return the params as that type
	 * @throws AcpProtocolException if the params cannot be converted to the type or a required
	 * field is missing; its code is {@link AcpErrorCodes#INVALID_PARAMS}
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
	 * Returns the ACP protocol versions this transport supports. The SDK does not read it when
	 * it negotiates a version: a client sends the version its {@code initialize} call names.
	 * The interface's default returns only {@link AcpSchema#LATEST_PROTOCOL_VERSION}.
	 * @return the supported protocol versions
	 */
	default List<Integer> protocolVersions() {
		return List.of(AcpSchema.LATEST_PROTOCOL_VERSION);
	}

}
