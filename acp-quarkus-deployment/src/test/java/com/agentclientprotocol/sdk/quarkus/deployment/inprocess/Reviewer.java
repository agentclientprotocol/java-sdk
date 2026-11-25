/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment.inprocess;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.integration.AcpPermissionHandler;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import jakarta.inject.Singleton;

/** A reviewer who approves every claim. */
@Singleton
public class Reviewer implements AcpPermissionHandler {

	final List<String> asked = new CopyOnWriteArrayList<>();

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

}
