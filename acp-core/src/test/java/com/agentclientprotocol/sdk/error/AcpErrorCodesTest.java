/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AcpErrorCodes}.
 */
class AcpErrorCodesTest {

	@Test
	void verifyStandardJsonRpcErrorCodes() {
		// Standard JSON-RPC error codes
		assertThat(AcpErrorCodes.PARSE_ERROR).isEqualTo(-32700);
		assertThat(AcpErrorCodes.INVALID_REQUEST).isEqualTo(-32600);
		assertThat(AcpErrorCodes.METHOD_NOT_FOUND).isEqualTo(-32601);
		assertThat(AcpErrorCodes.INVALID_PARAMS).isEqualTo(-32602);
		assertThat(AcpErrorCodes.INTERNAL_ERROR).isEqualTo(-32603);
	}

	/**
	 * ACP v1 schema, {@code $defs.ErrorCode}: the only codes ACP defines in its reserved
	 * -32000..-32099 range are -32000 (authentication required) and -32002 (resource not
	 * found), plus -32800 (request cancelled).
	 */
	@Test
	void verifyAcpSpecificErrorCodes() {
		assertThat(AcpErrorCodes.REQUEST_CANCELLED).isEqualTo(-32800);
		assertThat(AcpErrorCodes.AUTHENTICATION_REQUIRED).isEqualTo(-32000);
		assertThat(AcpErrorCodes.RESOURCE_NOT_FOUND).isEqualTo(-32002);
	}

	/** Every public constant is a code the ACP v1 schema defines, and no two share a value. */
	@Test
	void everyConstantIsASpecErrorCode() throws IllegalAccessException {
		java.util.Set<Integer> spec = java.util.Set.of(-32700, -32600, -32601, -32602, -32603, -32800, -32000,
				-32002);
		java.util.List<Integer> values = new java.util.ArrayList<>();
		for (java.lang.reflect.Field field : AcpErrorCodes.class.getFields()) {
			if (field.getType() == int.class) {
				values.add(field.getInt(null));
			}
		}
		assertThat(values).hasSize(spec.size()).doesNotHaveDuplicates();
		assertThat(spec).containsAll(values);
	}

	@Test
	void getDescriptionReturnsCorrectMessages() {
		assertThat(AcpErrorCodes.getDescription(AcpErrorCodes.PARSE_ERROR)).isEqualTo("Parse error");
		assertThat(AcpErrorCodes.getDescription(AcpErrorCodes.METHOD_NOT_FOUND)).isEqualTo("Method not found");
		assertThat(AcpErrorCodes.getDescription(-32000)).isEqualTo("Authentication required");
		assertThat(AcpErrorCodes.getDescription(-32002)).isEqualTo("Resource not found");
		assertThat(AcpErrorCodes.getDescription(-32800)).isEqualTo("Request cancelled");
	}

	@Test
	void getDescriptionReturnsUnknownForUnrecognizedCodes() {
		assertThat(AcpErrorCodes.getDescription(-99999)).isEqualTo("Unknown error");
		assertThat(AcpErrorCodes.getDescription(0)).isEqualTo("Unknown error");
	}

}
