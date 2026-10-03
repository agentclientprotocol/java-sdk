/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@code @AcpAgent} bean that declares a normal scope (so the container hands out a
 * client proxy) and whose handler carries a CDI interceptor binding (so the instance is
 * a container subclass) is still served: handlers are found on the user's class and
 * invoked through the container's instance, interceptor included.
 */
class ProxiedAgentTest {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(ScopedAgent.class, Audited.class, AuditInterceptor.class,
				InMemoryAgentTransport.class))
		.overrideConfigKey("quarkus.acp.agent.shutdown-on-transport-end", "false");

	@Inject
	InMemoryAgentTransport transport;

	@Test
	void proxiedAndInterceptedAgentIsServed() {
		assertThat(Arc.container().instance(ScopedAgent.class).get()).isInstanceOf(ClientProxy.class);

		AcpSyncClient client = transport.client();
		client.initialize();
		String sessionId = client.newSession(InMemoryAgentTransport.newSession()).sessionId();
		AcpSchema.PromptResponse response = client.prompt(InMemoryAgentTransport.prompt(sessionId, "hi"));

		assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(transport.messages).containsExactly("scoped hi");
		assertThat(AuditInterceptor.invoked).containsExactly("prompt");
	}

	@AcpAgent
	@ApplicationScoped
	public static class ScopedAgent {

		@Prompt
		@Audited
		AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
			context.sendMessage("scoped " + ((AcpSchema.TextContent) request.prompt().get(0)).text());
			return AcpSchema.PromptResponse.endTurn();
		}

	}

	@InterceptorBinding
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ ElementType.METHOD, ElementType.TYPE })
	public @interface Audited {

	}

	@Audited
	@Interceptor
	@Priority(Interceptor.Priority.APPLICATION)
	public static class AuditInterceptor {

		static final List<String> invoked = new CopyOnWriteArrayList<>();

		@AroundInvoke
		Object audit(InvocationContext context) throws Exception {
			invoked.add(context.getMethod().getName());
			return context.proceed();
		}

	}

}
