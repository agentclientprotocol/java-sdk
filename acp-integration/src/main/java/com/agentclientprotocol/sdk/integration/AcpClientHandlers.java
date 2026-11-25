/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.util.List;
import java.util.concurrent.ExecutorService;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.HandlerFailures;
import com.agentclientprotocol.sdk.util.PlatformThreads;
import com.agentclientprotocol.sdk.util.VirtualThreads;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The application's handler beans for the client a framework builds: every
 * {@link AcpSessionUpdateHandler} and at most one {@link AcpPermissionHandler}. The framework
 * collects them in its bean order and passes them to {@link AcpClients#async}, which registers them
 * on the client's builder before the {@link AcpClientCustomizer}s run.
 *
 * <p>The handlers may block: each call runs on a thread of the SDK's own, named
 * {@code acp-client-handler}: a virtual thread per call on JDK 21 and later, before that a cached
 * pool of daemon threads, so neither keeps the JVM alive. Without a permission handler the client refuses every permission
 * request ({@link #refuse}); a customizer that registers one with {@code requestPermissionHandler}
 * replaces that default, and so does the bean, but a bean and a customizer together fail, since a
 * method has one handler.
 *
 * @param sessionUpdateHandlers the update handlers, in order; each receives every update
 * @param permissionHandler the permission handler, or null to refuse every request
 * @author Mark Pollack
 */
public record AcpClientHandlers(List<AcpSessionUpdateHandler> sessionUpdateHandlers,
		@Nullable AcpPermissionHandler permissionHandler) {

	private static final Logger logger = LoggerFactory.getLogger(AcpClientHandlers.class);

	/** No handler beans: updates are logged at DEBUG and permission requests are refused. */
	public static final AcpClientHandlers NONE = new AcpClientHandlers(List.of(), null);

	/**
	 * Creates the handlers, copying the list.
	 */
	public AcpClientHandlers {
		sessionUpdateHandlers = List.copyOf(sessionUpdateHandlers);
	}

	/**
	 * Returns the handlers from the beans a framework found.
	 * @param sessionUpdateHandlers every session-update handler bean, in bean order
	 * @param permissionHandlers every permission handler bean; at most one is allowed
	 * @return the handlers
	 * @throws IllegalStateException if there is more than one permission handler; the message
	 * names their classes
	 */
	public static AcpClientHandlers of(List<? extends AcpSessionUpdateHandler> sessionUpdateHandlers,
			List<? extends AcpPermissionHandler> permissionHandlers) {
		if (permissionHandlers.size() > 1) {
			throw new IllegalStateException("The ACP client takes one AcpPermissionHandler bean, found "
					+ permissionHandlers.size() + ": "
					+ permissionHandlers.stream().map(handler -> handler.getClass().getName()).toList());
		}
		return new AcpClientHandlers(List.copyOf(sessionUpdateHandlers),
				permissionHandlers.isEmpty() ? null : permissionHandlers.get(0));
	}

	/**
	 * Registers the handlers on the client's builder, and the refusing default for permission
	 * requests.
	 * @param spec the client's builder
	 */
	void register(com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec spec) {
		for (AcpSessionUpdateHandler handler : sessionUpdateHandlers) {
			spec.sessionUpdateHandler(notification -> Mono
				.fromRunnable(HandlerFailures.guard(() -> handler.onSessionUpdate(notification)))
				.subscribeOn(Threads.HANDLERS)
				.then()
				.onErrorResume(error -> {
					logger.warn("An AcpSessionUpdateHandler failed: {}", error.toString(), error);
					return Mono.empty();
				}));
		}
		AcpPermissionHandler permission = this.permissionHandler;
		if (permission != null) {
			spec.requestPermissionHandler(request -> Mono
				.fromCallable(HandlerFailures.guard(() -> permission.onPermissionRequest(request)))
				.subscribeOn(Threads.HANDLERS));
		}
		spec.defaultRequestPermissionHandler(request -> Mono.just(refuse(request)));
	}

	/** The handler beans' threads, created on first use. */
	static final class Threads {

		static final Scheduler HANDLERS = Schedulers
			.fromExecutorService(executor(VirtualThreads.newPerTaskExecutor("acp-client-handler")), "acp-client-handlers");

		/** The virtual-thread executor when the JDK has one, else a cached pool of daemon threads. */
		static ExecutorService executor(@Nullable ExecutorService virtual) {
			return (virtual != null) ? virtual : PlatformThreads.newCachedPool("acp-client-handler");
		}

	}

	/**
	 * The answer to a permission request nobody handles: the first reject option the agent
	 * offered ({@code reject_once}, else {@code reject_always}), or {@code cancelled} when it
	 * offered none.
	 * @param request the request
	 * @return the refusal
	 */
	public static AcpSchema.RequestPermissionResponse refuse(AcpSchema.RequestPermissionRequest request) {
		for (AcpSchema.PermissionOptionKind kind : List.of(AcpSchema.PermissionOptionKind.REJECT_ONCE,
				AcpSchema.PermissionOptionKind.REJECT_ALWAYS)) {
			for (AcpSchema.PermissionOption option : request.options()) {
				if (kind.equals(option.kind())) {
					return new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected(option.optionId()));
				}
			}
		}
		return new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionCancelled());
	}

}
