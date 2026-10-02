/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.StopReason;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One {@link AcpAgentSupport.Builder} builds any number of agents: {@code build()} composes
 * the default resolvers and return value handlers at build time without changing the
 * builder, and {@code buildFactory()} serves one agent per connection of a listener
 * transport from the same handler bean.
 */
class AcpAgentSupportBuilderReuseTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@AcpAgent
	static class CwdAgent {

		final AtomicInteger sessions = new AtomicInteger();

		@NewSession
		NewSessionResponse newSession(NewSessionRequest request) {
			sessions.incrementAndGet();
			return new NewSessionResponse(request.cwd(), null, null);
		}

	}

	private static String sessionIdFrom(AcpAgentSupport.Builder builder) {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpAgentSupport agent = builder.transport(pair.agentTransport()).requestTimeout(TIMEOUT).build();
		agent.start();
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).requestTimeout(TIMEOUT).build();
		try {
			client.initialize().block(TIMEOUT);
			return client.newSession(new NewSessionRequest("/workspace", List.of())).block(TIMEOUT).sessionId();
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
			agent.close();
		}
	}

	@Test
	void buildingTwiceGivesIdenticalAgents() {
		CwdAgent bean = new CwdAgent();
		AcpAgentSupport.Builder builder = AcpAgentSupport.create(bean);

		assertThat(sessionIdFrom(builder)).isEqualTo("/workspace");
		assertThat(sessionIdFrom(builder)).isEqualTo("/workspace");
		assertThat(bean.sessions).hasValue(2);
	}

	/**
	 * Custom resolvers take precedence over the defaults. Before, the first build appended the
	 * defaults to the builder's own list, so a resolver added after it came after the
	 * defaults and never ran.
	 */
	@Test
	void aCustomResolverAddedAfterABuildStillTakesPrecedence() {
		AcpAgentSupport.Builder builder = AcpAgentSupport.create(new CwdAgent());
		assertThat(sessionIdFrom(builder)).isEqualTo("/workspace");

		builder.argumentResolver(new ArgumentResolver() {
			@Override
			public boolean supportsParameter(AcpMethodParameter parameter) {
				return parameter.getParameterType() == NewSessionRequest.class;
			}

			@Override
			public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
				return new NewSessionRequest("/custom", List.of());
			}
		});

		assertThat(sessionIdFrom(builder)).isEqualTo("/custom");
	}

	/** One bean behind every connection; prompts on two connections run at the same time. */
	@AcpAgent
	static class ConcurrentPromptAgent {

		final CountDownLatch bothPrompting = new CountDownLatch(2);

		final Set<String> sessions = ConcurrentHashMap.newKeySet();

		@NewSession
		NewSessionResponse newSession(NewSessionRequest request) {
			sessions.add(request.cwd());
			return new NewSessionResponse(request.cwd(), null, null);
		}

		@Prompt
		PromptResponse prompt(PromptRequest request) throws InterruptedException {
			bothPrompting.countDown();
			// Each prompt waits for the other connection's: both are served at once.
			if (!bothPrompting.await(5, TimeUnit.SECONDS)) {
				return PromptResponse.refusal();
			}
			return PromptResponse.endTurn();
		}

	}

	@Test
	void aFactoryServesTwoConnectionsAtOnceOverStreamableHttp() {
		ConcurrentPromptAgent bean = new ConcurrentPromptAgent();
		AcpAgentFactory factory = AcpAgentSupport.create(bean).requestTimeout(TIMEOUT).buildFactory();
		StreamableHttpAcpAgentTransport server = new StreamableHttpAcpAgentTransport(0, AcpJsonMapper.createDefault(),
				factory);
		server.start().block(TIMEOUT);
		AcpAsyncClient first = client(server);
		AcpAsyncClient second = client(server);
		try {
			Mono<StopReason> firstTurn = turn(first, "/one");
			Mono<StopReason> secondTurn = turn(second, "/two");

			assertThat(Mono.zip(firstTurn, secondTurn).block(TIMEOUT).toList())
				.containsExactly(StopReason.END_TURN, StopReason.END_TURN);
			assertThat(bean.sessions).containsExactlyInAnyOrder("/one", "/two");
		}
		finally {
			first.closeGracefully().block(TIMEOUT);
			second.closeGracefully().block(TIMEOUT);
			server.closeGracefully().block(TIMEOUT);
		}
	}

	private static AcpAsyncClient client(StreamableHttpAcpAgentTransport server) {
		return AcpClient
			.async(new StreamableHttpAcpClientTransport(
					URI.create("http://127.0.0.1:" + server.getPort() + StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH),
					AcpJsonMapper.createDefault()))
			.requestTimeout(TIMEOUT)
			.build();
	}

	private static Mono<StopReason> turn(AcpAsyncClient client, String cwd) {
		return client.initialize()
			.then(client.newSession(new NewSessionRequest(cwd, List.of())))
			.flatMap(session -> client
				.prompt(new PromptRequest(session.sessionId(), List.of(new TextContent("hi")))))
			.map(PromptResponse::stopReason);
	}

}
