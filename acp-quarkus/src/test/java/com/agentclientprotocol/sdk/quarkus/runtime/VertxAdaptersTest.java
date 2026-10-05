/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.security.Principal;
import java.util.Map;

import com.agentclientprotocol.sdk.http.server.AcpHttpReply;
import com.agentclientprotocol.sdk.http.server.SseFrame;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The edges of the Vert.x adapters that a running server does not reach on demand: a body a
 * handler already read, the principal of each kind of user, a client gone before the reply, a
 * failed SSE write, a closed socket.
 */
class VertxAdaptersTest {

	@Test
	void aBodyAHandlerAlreadyReadIsUsed() {
		RoutingContext context = mock(RoutingContext.class);
		RequestBody body = mock(RequestBody.class);
		when(context.body()).thenReturn(body);
		when(body.available()).thenReturn(true);
		when(body.buffer()).thenReturn(Buffer.buffer("{}"));
		assertThat(new VertxExchange(context).body(10).block()).isEqualTo("{}".getBytes());
		when(body.buffer()).thenReturn(null);
		assertThat(new VertxExchange(context).body(10).block()).isEmpty();
	}

	@Test
	void onlyAnAuthenticatedQuarkusUserIsAPrincipal() {
		RoutingContext context = mock(RoutingContext.class);
		VertxExchange exchange = new VertxExchange(context);
		assertThat(exchange.principal()).isNull();

		when(context.user()).thenReturn(mock(User.class));
		assertThat(exchange.principal()).isNull();

		SecurityIdentity identity = mock(SecurityIdentity.class);
		QuarkusHttpUser user = mock(QuarkusHttpUser.class);
		when(user.getSecurityIdentity()).thenReturn(identity);
		when(context.user()).thenReturn(user);
		when(identity.isAnonymous()).thenReturn(true);
		assertThat(exchange.principal()).isNull();

		Principal alice = () -> "alice";
		when(identity.isAnonymous()).thenReturn(false);
		when(identity.getPrincipal()).thenReturn(alice);
		assertThat(exchange.principal()).isSameAs(alice);
	}

	@Test
	void aReplyToAClientThatLeftIsDropped() {
		RoutingContext context = mock(RoutingContext.class);
		HttpServerResponse response = mock(HttpServerResponse.class);
		when(context.response()).thenReturn(response);
		when(response.closed()).thenReturn(true);
		AcpVertxHost.write(context, new AcpHttpReply.Empty(202, Map.of()));
		verify(response, never()).setStatusCode(any(Integer.class));
	}

	@Test
	void aFailedSseWriteCancelsTheStreamAndEndsTheResponse() {
		HttpServerResponse response = mock(HttpServerResponse.class);
		when(response.write(any(Buffer.class))).thenReturn(Future.failedFuture("reset"));
		Subscription subscription = mock(Subscription.class);
		VertxSseWriter writer = new VertxSseWriter(response);
		writer.onSubscribe(subscription);
		writer.onNext(SseFrame.comment("connected"));
		verify(subscription).cancel();
		verify(response).end();
		writer.onNext(SseFrame.comment("ignored"));
		writer.onError(new IllegalStateException("late"));
		writer.cancel();
	}

	@Test
	void aCompletedStreamToAClosedResponseIsNotEnded() {
		HttpServerResponse response = mock(HttpServerResponse.class);
		when(response.closed()).thenReturn(true);
		VertxSseWriter writer = new VertxSseWriter(response);
		Flux.<SseFrame>empty().subscribe(writer);
		verify(response, never()).end();
	}

	@Test
	void aClosedSocketIsNotClosedAgain() {
		ServerWebSocket socket = mock(ServerWebSocket.class);
		when(socket.isClosed()).thenReturn(true);
		new VertxWsOutbound(socket).close(1001, "bye");
		verify(socket, never()).close(any(Short.class), any(String.class));
	}

}
