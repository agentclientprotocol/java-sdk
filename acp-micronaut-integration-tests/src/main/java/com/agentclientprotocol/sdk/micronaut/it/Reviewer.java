/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.integration.AcpPermissionHandler;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/** A reviewer who approves every claim; absent with {@code test.reviewer=false}. */
@Singleton
@Requires(property = "test.reviewer", notEquals = "false")
public class Reviewer implements AcpPermissionHandler {

	private final List<String> asked = new CopyOnWriteArrayList<>();

	@Override
	public AcpSchema.RequestPermissionResponse onPermissionRequest(AcpSchema.RequestPermissionRequest request) {
		asked.add(String.valueOf(request.toolCall().title()));
		AcpSchema.PermissionOption allow = request.options()
			.stream()
			.filter(option -> option.kind().equals(AcpSchema.PermissionOptionKind.ALLOW_ONCE))
			.findFirst()
			.orElseThrow();
		return new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected(allow.optionId()));
	}

	/**
	 * The permission requests answered so far.
	 * @return their tool call titles
	 */
	public List<String> asked() {
		return List.copyOf(asked);
	}

}
