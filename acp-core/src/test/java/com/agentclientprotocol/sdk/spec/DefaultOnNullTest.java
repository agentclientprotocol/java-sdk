/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The response types that read {@code "result": null} as {@code {}}, as the Rust SDK's
 * {@code default_on_null} payloads do. Every JSON module runs this class.
 */
class DefaultOnNullTest {

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	@Test
	void theDefaultOnNullResponsesAreExactlyThoseOfTheRustSchema() {
		// agent-client-protocol-schema 1.9.1 v1: every response declared with default_on_null!.
		assertThat(defaultOnNullTypes()).extracting(Class::getSimpleName)
			.containsExactlyInAnyOrder("AuthenticateResponse", "LogoutResponse", "LoadSessionResponse",
					"ResumeSessionResponse", "CloseSessionResponse", "DeleteSessionResponse", "SetSessionModeResponse",
					"WriteTextFileResponse", "ReleaseTerminalResponse",
					"KillTerminalCommandResponse", "WaitForTerminalExitResponse", "SetProviderResponse",
					"DisableProviderResponse");
	}

	@Test
	void aResponseDefaultsOnNullExactlyWhenEveryComponentIsOptional() {
		for (Class<?> type : AcpSchema.class.getDeclaredClasses()) {
			if (!type.isRecord() || !type.getSimpleName().endsWith("Response") || type == AcpSchema.JSONRPCResponse.class) {
				continue;
			}
			boolean allOptional = Arrays.stream(type.getRecordComponents())
				.allMatch(component -> component.getAnnotatedType().isAnnotationPresent(Nullable.class));
			assertThat(AcpSchema.DefaultOnNull.class.isAssignableFrom(type)).as(type.getSimpleName())
				.isEqualTo(allOptional);
		}
	}

	@Test
	void everyEmptyResponseWritesExactlyAnEmptyObject() throws Exception {
		for (Class<?> type : defaultOnNullTypes()) {
			Object empty = emptyInstance(type);
			assertThat(mapper.writeValueAsString(empty)).as(type.getSimpleName()).isEqualTo("{}");
			assertThat(mapper.convertValue(Map.of(), type)).as(type.getSimpleName()).isEqualTo(empty);
		}
	}

	@Test
	void aNullResultAndAMissingResultBothReadAsAResponseWithoutResult() throws IOException {
		for (String json : List.of("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":null}", "{\"jsonrpc\":\"2.0\",\"id\":7}")) {
			AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(mapper, json);
			assertThat(message).as(json).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) message;
			assertThat(response.id()).isEqualTo(7);
			assertThat(response.result()).isNull();
			assertThat(response.error()).isNull();
		}
	}

	private static List<Class<?>> defaultOnNullTypes() {
		return Arrays.stream(AcpSchema.class.getDeclaredClasses())
			.filter(AcpSchema.DefaultOnNull.class::isAssignableFrom)
			.filter(type -> type != AcpSchema.DefaultOnNull.class)
			.toList();
	}

	private static Object emptyInstance(Class<?> type) throws ReflectiveOperationException {
		RecordComponent[] components = type.getRecordComponents();
		Class<?>[] parameterTypes = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
		return type.getDeclaredConstructor(parameterTypes).newInstance(new Object[components.length]);
	}

}
