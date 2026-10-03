/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpStdioAgentHost;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@code @AcpAgent} class with no scope annotation becomes a singleton bean with its
 * collaborators injected, and its handlers are served with the application's interceptor
 * and argument resolver beans, over the agent transport bean.
 */
class AgentBeanTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(EchoAgent.class, Shouter.class, CountingInterceptor.class,
				SuffixResolver.class, Suffix.class, InMemoryAgentTransport.class))
		.overrideConfigKey("quarkus.acp.agent.shutdown-on-transport-end", "false");

	@Inject
	InMemoryAgentTransport transport;

	@Inject
	CountingInterceptor interceptor;

	@Inject
	AcpStdioAgentHost host;

	@Test
	void agentClassIsASingletonBeanWithoutAClientProxy() {
		var bean = Arc.container().instance(EchoAgent.class).getBean();
		assertThat(bean.getScope()).isEqualTo(Singleton.class);
		assertThat(Arc.container().instance(EchoAgent.class).get().getClass()).isEqualTo(EchoAgent.class);
		assertThat(host.agent()).isNotNull();
	}

	@Test
	void stdioKeepsStandardOutputForTheProtocol() {
		var config = ConfigProvider.getConfig();
		assertThat(config.getValue("quarkus.log.console.stderr", Boolean.class)).isTrue();
		assertThat(config.getValue("quarkus.banner.enabled", Boolean.class)).isFalse();
		assertThat(config.getValue("quarkus.http.host-enabled", Boolean.class)).isFalse();
	}

	@Test
	void promptIsServedWithInjectedCollaboratorsInterceptorsAndResolvers() {
		AcpSyncClient client = transport.client();
		AcpSchema.InitializeResponse initialized = client.initialize();
		// Derived from the annotations: agentInfo from @AcpAgent, no capability beyond the
		// baseline (the agent declares only @Prompt).
		assertThat(initialized.protocolVersion()).isEqualTo(AcpSchema.LATEST_PROTOCOL_VERSION);
		assertThat(initialized.agentInfo()).isNotNull();
		assertThat(initialized.agentInfo().name()).isEqualTo("echo");
		assertThat(initialized.agentCapabilities().loadSession()).isFalse();

		String sessionId = client.newSession(InMemoryAgentTransport.newSession()).sessionId();
		AcpSchema.PromptResponse response = client.prompt(InMemoryAgentTransport.prompt(sessionId, "hello"));

		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(transport.messages).containsExactly("HELLO!");
		// initialize and session/new have no handler here (the SDK defaults answer them).
		assertThat(interceptor.methods).containsExactly("session/prompt");
	}

	@AcpAgent(name = "echo")
	public static class EchoAgent {

		private final Shouter shouter;

		@Inject
		EchoAgent(Shouter shouter) {
			this.shouter = shouter;
		}

		@Prompt
		AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context, Suffix suffix) {
			AcpSchema.TextContent text = (AcpSchema.TextContent) request.prompt().get(0);
			context.sendMessage(shouter.shout(text.text()) + suffix.value());
			return AcpSchema.PromptResponse.endTurn();
		}

	}

	@ApplicationScoped
	public static class Shouter {

		String shout(String text) {
			return text.toUpperCase(java.util.Locale.ROOT);
		}

	}

	/** A value the agent's handler declares, resolved by an application resolver bean. */
	public record Suffix(String value) {
	}

	@Singleton
	public static class SuffixResolver implements ArgumentResolver {

		@Override
		public boolean supportsParameter(AcpMethodParameter parameter) {
			return parameter.getParameterType() == Suffix.class;
		}

		@Override
		public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
			return new Suffix("!");
		}

	}

	@Singleton
	public static class CountingInterceptor implements AcpInterceptor {

		final List<String> methods = new CopyOnWriteArrayList<>();

		@Override
		public boolean preInvoke(AcpInvocationContext context) {
			methods.add(context.getAcpMethod());
			return true;
		}

	}

}
