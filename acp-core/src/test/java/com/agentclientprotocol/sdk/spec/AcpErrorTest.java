/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The message an {@link AcpError} carries names the peer's message and code, and the
 * most useful part of its data.
 */
class AcpErrorTest {

	private static AcpError error(Object data) {
		return new AcpError(new AcpSchema.JSONRPCError(-32602, "Invalid params", data));
	}

	@Test
	void exposesTheErrorItWasBuiltFrom() {
		AcpSchema.JSONRPCError jsonRpcError = new AcpSchema.JSONRPCError(-32602, "Invalid params", List.of(1));
		AcpError error = new AcpError(jsonRpcError);
		assertThat(error.getError()).isSameAs(jsonRpcError);
		assertThat(error.getCode()).isEqualTo(-32602);
		assertThat(error.getData()).isEqualTo(List.of(1));
	}

	@Test
	void messageWithoutDataIsThePeersMessageAlone() {
		assertThat(error(null)).hasMessage("Invalid params");
	}

	@Test
	void messagePrefersTheDetailsOfMapData() {
		assertThat(error(Map.of("details", "path must be absolute", "reason", "bad path")))
			.hasMessage("Invalid params: path must be absolute");
	}

	@Test
	void messageFallsBackToTheReasonOfMapData() {
		assertThat(error(Map.of("reason", "bad path"))).hasMessage("Invalid params: bad path");
	}

	@Test
	void messageShowsOtherDataWhole() {
		assertThat(error(Map.of("line", 3))).hasMessage("Invalid params: {line=3}");
		assertThat(error("plain")).hasMessage("Invalid params: plain");
	}

	@Test
	void theCodeIsNamedOnceWhenPrintedWithTheMessage() {
		AcpError error = error(null);
		assertThat(error.getCode() + " " + error.getMessage()).isEqualTo("-32602 Invalid params");
	}

	@Test
	void toStringNamesTheCode() {
		assertThat(error("plain"))
			.hasToString("com.agentclientprotocol.sdk.spec.AcpError: Invalid params [code=-32602]: plain");
	}

}
