/*
 * Copyright 2025-2025 the original author or authors.
 */
package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that handler exceptions are properly converted to JSON-RPC error responses.
 * This validates the correct error handling pattern for SDK users.
 */
class HandlerExceptionTest {

    /**
     * Verifies that when a typed handler throws an exception, the SDK converts it
     * to a proper JSON-RPC error response with code -32603 (Internal Error).
     */
    @Test
    void handlerExceptionConvertedToJsonRpcError() {
        MockAcpClientTransport transport = new MockAcpClientTransport();
        String errorMessage = "File not found: /nonexistent.txt";

        // Create a handler that throws an exception
        Function<AcpSchema.ReadTextFileRequest, AcpSchema.ReadTextFileResponse> handler = req -> {
            throw new RuntimeException(errorMessage);
        };

        AcpSyncClient client = AcpClient.sync(transport)
            .readTextFileHandler(handler)
            .build();

        // Simulate incoming request
        AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
                AcpSchema.JSONRPC_VERSION,
                "test-id",
                AcpSchema.METHOD_FS_READ_TEXT_FILE,
                Map.of("sessionId", "session-123", "path", "/nonexistent.txt")
        );
        transport.simulateIncomingMessage(request);

        awaitSentMessages(transport, 1);

        // Verify it's a JSON-RPC error response
        assertThat(transport.getSentMessages()).hasSize(1);
        assertThat(transport.getSentMessages().get(0)).isInstanceOf(AcpSchema.JSONRPCResponse.class);
        AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) transport.getSentMessages().get(0);

        assertThat(response.id()).isEqualTo("test-id");
        assertThat(response.result()).isNull();
        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(-32603); // Internal Error
        assertThat(response.error().message()).contains(errorMessage);

        client.close();
    }

    /**
     * Verifies that IOException from file operations is also converted to JSON-RPC error.
     */
    @Test
    void ioExceptionConvertedToJsonRpcError() {
        MockAcpClientTransport transport = new MockAcpClientTransport();

        // Create a handler that throws IOException (wrapped in RuntimeException per Java patterns)
        Function<AcpSchema.WriteTextFileRequest, AcpSchema.WriteTextFileResponse> handler = req -> {
            throw new RuntimeException(new java.io.IOException("Permission denied: " + req.path()));
        };

        AcpSyncClient client = AcpClient.sync(transport)
            .writeTextFileHandler(handler)
            .build();

        AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
                AcpSchema.JSONRPC_VERSION,
                "test-id-2",
                AcpSchema.METHOD_FS_WRITE_TEXT_FILE,
                Map.of("sessionId", "session-123", "path", "/readonly/file.txt", "content", "test")
        );
        transport.simulateIncomingMessage(request);

        awaitSentMessages(transport, 1);

        assertThat(transport.getSentMessages()).hasSize(1);
        AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) transport.getSentMessages().get(0);
        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(-32603);
        assertThat(response.error().message()).contains("Permission denied");

        client.close();
    }

    /**
     * Waits for the transport to have sent at least {@code expected} messages. The
     * handler runs asynchronously, so a fixed sleep races the dispatch and fails
     * intermittently on a loaded machine; poll to a generous deadline instead.
     */
    /**
     * JSON-RPC 2.0 section 5.1: params the method cannot read are -32602 Invalid params, not
     * -32603 Internal error, and the handler is never called.
     */
    @Test
    void paramsOfTheWrongTypeAreInvalidParams() {
        MockAcpClientTransport transport = new MockAcpClientTransport();
        java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
        AcpSyncClient client = AcpClient.sync(transport).readTextFileHandler(req -> {
            called.set(true);
            return new AcpSchema.ReadTextFileResponse("x");
        }).build();

        transport.simulateIncomingMessage(new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "bad-params",
                AcpSchema.METHOD_FS_READ_TEXT_FILE, Map.of("sessionId", "s", "path", "/f", "line", "not-a-number")));
        awaitSentMessages(transport, 1);

        AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) transport.getSentMessages().get(0);
        assertThat(response.id()).isEqualTo("bad-params");
        assertThat(response.error().code()).isEqualTo(-32602);
        assertThat(response.error().message()).isEqualTo("Invalid params");
        assertThat(called).isFalse();
        client.close();
    }

    private static void awaitSentMessages(MockAcpClientTransport transport, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (transport.getSentMessages().size() < expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
