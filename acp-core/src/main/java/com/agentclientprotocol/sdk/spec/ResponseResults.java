/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;

import com.agentclientprotocol.sdk.json.TypeRef;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.SynchronousSink;

/**
 * Delivers the result of a successful JSON-RPC response to the caller of
 * {@link AcpSession#sendRequest}, the same way for both session sides.
 */
final class ResponseResults {

	private ResponseResults() {
	}

	/**
	 * Delivers a success response's result. A null or missing result reads as {@code {}}
	 * for a {@link AcpSchema.DefaultOnNull} response type, completes empty for an
	 * extension ({@code _}-prefixed) method, whose result may legally be null, and
	 * otherwise fails the request. A result that cannot be read as the type, or lacks a
	 * field the schema requires, fails the request too. Each failure is an {@link AcpError}
	 * with -32603 (internal error), as a peer's error response is: JSON-RPC 2.0 defines no
	 * code for an invalid response, and the failure is the caller's, not the peer's to be
	 * told about.
	 */
	static <T> void deliver(String method, @Nullable Object result, TypeRef<T> typeRef, AcpTransport transport,
			SynchronousSink<T> sink) {
		if (result != null) {
			T value;
			try {
				value = transport.unmarshalFrom(result, typeRef);
			}
			catch (IllegalArgumentException e) {
				sink.error(AcpError.rejectedResponse(method, "unreadable-result",
						"The response to " + method + " could not be read: " + firstLine(e.getMessage()),
						Map.of()));
				return;
			}
			String missing = RequiredFields.firstMissing(value);
			if (missing != null) {
				sink.error(AcpError.rejectedResponse(method, "missing-required-field",
						"The response to " + method + " lacks the required field " + missing,
						Map.of("field", missing)));
				return;
			}
			sink.next(value);
		}
		else if (AcpSchema.DefaultOnNull.class.isAssignableFrom(rawClass(typeRef.getType()))) {
			sink.next(transport.unmarshalFrom(Map.of(), typeRef));
		}
		else if (ExtensionMethods.isExtension(method)) {
			sink.complete();
		}
		else {
			sink.error(AcpError.rejectedResponse(method, "missing-result",
					"The response to " + method + " carried no result", Map.of()));
		}
	}

	private static String firstLine(@Nullable String message) {
		if (message == null) {
			return "the result does not fit the method's result type";
		}
		int end = message.indexOf('\n');
		return end < 0 ? message : message.substring(0, end);
	}

	private static Class<?> rawClass(Type type) {
		if (type instanceof Class<?> clazz) {
			return clazz;
		}
		if (type instanceof ParameterizedType parameterized) {
			return rawClass(parameterized.getRawType());
		}
		return Object.class;
	}

}
