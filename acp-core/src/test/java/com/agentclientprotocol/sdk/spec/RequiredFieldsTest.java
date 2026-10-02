/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The required-field check covers the schema's records only: an application's own record,
 * such as an extension method's params, has no schema behind it, and one this package cannot
 * read must not be reported as missing its fields.
 */
class RequiredFieldsTest {

	private record Ping(String text) {
	}

	private record Nested(Ping ping, List<Ping> pings) {
	}

	@Test
	void schemaRecordMissingARequiredFieldIsReported() {
		AcpSchema.PromptRequest request = new AcpSchema.PromptRequest(null, List.of());

		assertThat(RequiredFields.firstMissing(request)).isEqualTo("sessionId");
	}

	@Test
	void completeSchemaRecordPasses() {
		assertThat(RequiredFields.firstMissing(new AcpSchema.PromptRequest("s", List.of()))).isNull();
	}

	@Test
	void applicationRecordsAreNotChecked() {
		assertThat(RequiredFields.firstMissing(new Ping("hi"))).isNull();
		assertThat(RequiredFields.firstMissing(new Ping(null))).isNull();
		assertThat(RequiredFields.firstMissing(new Nested(new Ping("a"), List.of(new Ping("b"))))).isNull();
		assertThat(RequiredFields.firstMissing(List.of(new Ping("c")))).isNull();
	}

}
