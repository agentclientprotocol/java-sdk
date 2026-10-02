/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.support.handler.DirectResponseHandler;
import com.agentclientprotocol.sdk.agent.support.invocation.AcpMethodParameter;
import com.agentclientprotocol.sdk.annotation.ExtNotification;
import com.agentclientprotocol.sdk.annotation.ExtRequest;
import com.agentclientprotocol.sdk.annotation.Initialize;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every handler an agent builder takes has a handler annotation that {@link AcpAgentSupport}
 * wires, and every handler annotation in acp-annotations is wired: an ACP method added to the
 * builders without an annotation, or an annotation without wiring, fails here.
 */
class HandlerAnnotationCoverageTest {

	/** Builder methods that take a handler per extension method name, served by @ExtRequest/@ExtNotification. */
	private static final Set<String> EXTENSION_HANDLER_SETTERS = Set.of("extRequestHandler", "extNotificationHandler");

	@Test
	void everySyncBuilderHandlerHasAnAnnotation() {
		assertThat(annotationHandlerSetters()).isEqualTo(handlerSetters(AcpAgent.SyncAgentBuilder.class));
	}

	@Test
	void everyAsyncBuilderHandlerHasAnAnnotation() {
		assertThat(annotationHandlerSetters()).isEqualTo(handlerSetters(AcpAgent.AsyncAgentBuilder.class));
	}

	@Test
	void everyHandlerAnnotationIsWired() throws Exception {
		Set<String> wired = AcpAgentSupport.handlerAnnotations()
			.keySet()
			.stream()
			.map(Class::getName)
			.collect(Collectors.toCollection(TreeSet::new));
		assertThat(wired).isEqualTo(methodAnnotationsInAcpAnnotations());
	}

	/** A handler annotated for a request may return the request's response type as it is. */
	@Test
	void everyResponseTypeIsReturnable() {
		DirectResponseHandler direct = new DirectResponseHandler();
		Set<String> unsupported = new TreeSet<>();
		for (Method setter : AcpAgent.SyncAgentBuilder.class.getMethods()) {
			if (setter.getDeclaringClass() != AcpAgent.SyncAgentBuilder.class || !setter.getName().endsWith("Handler")
					|| EXTENSION_HANDLER_SETTERS.contains(setter.getName())) {
				continue;
			}
			Method handle = Arrays.stream(setter.getParameterTypes()[0].getMethods())
				.filter(method -> Modifier.isAbstract(method.getModifiers()))
				.findFirst()
				.orElseThrow();
			if (handle.getReturnType() != void.class
					&& !direct.supportsReturnType(AcpMethodParameter.forReturnType(handle))) {
				unsupported.add(handle.getReturnType().getSimpleName());
			}
		}
		assertThat(unsupported).isEmpty();
	}

	@Test
	void eachAnnotationMarksADistinctMethod() {
		var methods = AcpAgentSupport.handlerAnnotations().values();
		assertThat(Set.copyOf(methods)).hasSameSizeAs(methods);
	}

	/** {@code @SetSessionMode} serves {@code setSessionModeHandler}, and so on. */
	private static Set<String> annotationHandlerSetters() {
		return AcpAgentSupport.handlerAnnotations()
			.keySet()
			.stream()
			.map(Class::getSimpleName)
			.map(name -> Character.toLowerCase(name.charAt(0)) + name.substring(1) + "Handler")
			.collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<String> handlerSetters(Class<?> builder) {
		return Arrays.stream(builder.getMethods())
			.filter(method -> method.getDeclaringClass() == builder)
			.map(Method::getName)
			.filter(name -> name.endsWith("Handler"))
			.filter(name -> !EXTENSION_HANDLER_SETTERS.contains(name))
			.collect(Collectors.toCollection(TreeSet::new));
	}

	/** The method-level annotations of acp-annotations, other than the extension ones. */
	private static Set<String> methodAnnotationsInAcpAnnotations() throws Exception {
		Set<String> found = new TreeSet<>();
		for (String className : annotationPackageClassNames()) {
			Class<?> type = Class.forName(className);
			if (!type.isAnnotation() || type == ExtRequest.class || type == ExtNotification.class) {
				continue;
			}
			Target target = type.getAnnotation(Target.class);
			if (target != null && List.of(target.value()).equals(List.of(ElementType.METHOD))) {
				found.add(className);
			}
		}
		return found;
	}

	private static List<String> annotationPackageClassNames() throws IOException, URISyntaxException {
		Path root = Path.of(Initialize.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		String prefix = Initialize.class.getPackageName().replace('.', '/') + "/";
		Stream<String> entries;
		if (Files.isDirectory(root)) {
			try (Stream<Path> paths = Files.walk(root)) {
				entries = paths.map(path -> root.relativize(path).toString().replace('\\', '/')).toList().stream();
			}
		}
		else {
			try (JarFile jar = new JarFile(root.toFile())) {
				entries = Collections.list(jar.entries()).stream().map(entry -> entry.getName()).toList().stream();
			}
		}
		return entries.filter(name -> name.startsWith(prefix) && name.endsWith(".class"))
			.map(name -> name.substring(0, name.length() - ".class".length()))
			.filter(name -> name.indexOf('/', prefix.length()) < 0 && !name.contains("$")
					&& !name.endsWith("package-info"))
			.map(name -> name.replace('/', '.'))
			.toList();
	}

}
