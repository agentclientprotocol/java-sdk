/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
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
	 * otherwise fails the request.
	 */
	static <T> void deliver(String method, @Nullable Object result, TypeRef<T> typeRef, AcpTransport transport,
			SynchronousSink<T> sink) {
		if (result != null) {
			sink.next(transport.unmarshalFrom(result, typeRef));
		}
		else if (AcpSchema.DefaultOnNull.class.isAssignableFrom(rawClass(typeRef.getType()))) {
			sink.next(transport.unmarshalFrom(Map.of(), typeRef));
		}
		else if (method.startsWith("_")) {
			sink.complete();
		}
		else {
			sink.error(new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR,
					"The response to " + method + " carried no result"));
		}
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
