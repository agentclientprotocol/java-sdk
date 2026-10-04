/*
 * Copyright 2025-2025 the original author or authors.
 */

/**
 * The SDK's exceptions and the JSON-RPC error codes ACP uses. Catch
 * {@link com.agentclientprotocol.sdk.error.AcpException} to handle every failure of a call in one
 * place, or one of its subclasses for a single case:
 *
 * <ul>
 * <li>{@link com.agentclientprotocol.sdk.spec.AcpError}: the peer answered the request with an
 * error, or with a response the SDK rejected</li>
 * <li>{@link com.agentclientprotocol.sdk.error.AcpProtocolException}: what a handler throws to
 * answer a request with a JSON-RPC error; the caller receives it as an {@code AcpError}</li>
 * <li>{@link com.agentclientprotocol.sdk.error.AcpCapabilityException}: a call needs a capability
 * the peer did not advertise, so the SDK refused it without sending it</li>
 * <li>{@link com.agentclientprotocol.sdk.error.AcpConnectionException}: the transport could not
 * send a message, or the connection ended</li>
 * <li>{@link com.agentclientprotocol.sdk.error.AcpTimeoutException}: a blocking call of the sync
 * API got no answer in time</li>
 * </ul>
 *
 * <p>
 * The error codes are constants of {@link com.agentclientprotocol.sdk.error.AcpErrorCodes}.
 *
 * @see com.agentclientprotocol.sdk.error.AcpErrorCodes
 */
@NullMarked
package com.agentclientprotocol.sdk.error;

import org.jspecify.annotations.NullMarked;
