/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link UnstableAcpApi} marks exactly the public API that serves a protocol method or field
 * the stable schema does not define. The unstable set is derived from the stable schema copy
 * ({@code schema/v1/schema.json}): an {@code AcpSchema.METHOD_*} constant whose method the
 * schema lacks is unstable, and so is everything that serves it: the agent builders' handler
 * setter and handler interface for it, its request and response types, and every public method
 * whose signature names an unstable type (the client methods among them). A record component
 * the schema's definition of the record lacks is unstable, and so are the
 * {@code NegotiatedCapabilities} members named for it. When a method is promoted into the
 * stable schema, the markers on it must go: a stable method's constant and setter fail here
 * while marked.
 *
 * <p>The marker has class retention, so it is read from the bytecode (ArchUnit), not by
 * reflection.
 */
class UnstableApiMarkerTest {

	private static final JavaClasses CLASSES = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
		.importPackages("com.agentclientprotocol.sdk");

	private static final JsonNode SCHEMA = readSchema();

	/** The protocol methods the stable schema defines. */
	private static final Set<String> STABLE_METHODS = stableMethods();

	/** The {@code AcpSchema.METHOD_*} constants, by field name. */
	private static final Map<String, String> METHOD_CONSTANTS = methodConstants();

	private static final Set<String> UNSTABLE_METHODS = unstableMethods();

	@Test
	void theSchemaCopyLeavesMethodsUnstable() {
		// The derivation is not vacuous: session/fork and providers/* are not in schema 1.9.1.
		assertThat(UNSTABLE_METHODS).contains(AcpSchema.METHOD_SESSION_FORK, AcpSchema.METHOD_PROVIDERS_LIST);
		assertThat(STABLE_METHODS).contains(AcpSchema.METHOD_SESSION_PROMPT);
	}

	@Test
	void methodConstantsAreMarkedExactlyWhenTheMethodIsUnstable() {
		List<String> violations = new ArrayList<>();
		JavaClass schema = CLASSES.get(AcpSchema.class);
		METHOD_CONSTANTS.forEach((field, method) -> {
			boolean marked = schema.getField(field).isAnnotatedWith(UnstableAcpApi.class);
			if (marked != UNSTABLE_METHODS.contains(method)) {
				violations.add("AcpSchema." + field + " (" + method + ")" + (marked ? " is marked" : " is not marked"));
			}
		});
		assertThat(violations).isEmpty();
	}

	@Test
	void builderHandlersAreMarkedExactlyWhenTheirMethodIsUnstable() throws Exception {
		List<String> violations = new ArrayList<>();
		for (Class<?> builderType : List.of(AcpAgent.AsyncAgentBuilder.class, AcpAgent.SyncAgentBuilder.class)) {
			for (Method setter : handlerSetters(builderType)) {
				String method = registeredMethod(builderType, setter);
				boolean unstable = UNSTABLE_METHODS.contains(method);
				String where = builderType.getSimpleName() + "." + setter.getName() + " (" + method + ")";
				if (isMarked(member(setter)) != unstable) {
					violations.add(where + (unstable ? " is not marked" : " is marked"));
				}
				Class<?> handlerType = setter.getParameterTypes()[0];
				if (isMarked(CLASSES.get(handlerType)) != unstable) {
					violations.add(where + ": " + handlerType.getSimpleName() + (unstable ? " is not marked" : " is marked"));
				}
				if (unstable) {
					for (Class<?> type : requestAndResponseTypes(handlerType)) {
						if (!isMarked(CLASSES.get(type))) {
							violations.add(where + ": " + type.getSimpleName() + " is not marked");
						}
					}
				}
			}
		}
		assertThat(violations).isEmpty();
	}

	@Test
	void publicMethodsNamingAnUnstableTypeAreMarked() {
		Set<String> unstableTypes = new HashSet<>();
		CLASSES.stream().filter(UnstableApiMarkerTest::isMarked).forEach(type -> unstableTypes.add(type.getName()));
		List<String> violations = new ArrayList<>();
		for (JavaClass javaClass : CLASSES) {
			if (isMarked(javaClass) || !isPublicApi(javaClass)) {
				continue;
			}
			Class<?> type = javaClass.reflect();
			for (Method method : type.getDeclaredMethods()) {
				if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic() || method.isBridge()) {
					continue;
				}
				boolean namesUnstable = Stream
					.concat(Stream.of(method.getGenericReturnType()), Arrays.stream(method.getGenericParameterTypes()))
					.anyMatch(t -> names(t, unstableTypes));
				if (namesUnstable && !isMarked(member(method))) {
					violations.add(type.getName() + "." + method.getName());
				}
			}
		}
		assertThat(violations).isEmpty();
	}

	@Test
	void recordComponentsTheSchemaLacksAreMarked() {
		List<String> violations = new ArrayList<>();
		for (String component : unstableComponents()) {
			String[] parts = component.split("#");
			Class<?> record = recordNamed(parts[0]);
			JavaMember accessor = CLASSES.get(record).getMethod(parts[1]);
			if (!isMarked(accessor)) {
				violations.add(component);
			}
		}
		assertThat(violations).isEmpty();
	}

	/**
	 * A convenience constructor that sets an unstable component is unstable too, although the
	 * record is not. Which parameter feeds which component is found by building the record with
	 * a value in one parameter and nulls elsewhere.
	 */
	@Test
	void constructorsSettingAnUnstableComponentAreMarked() throws Exception {
		List<String> violations = new ArrayList<>();
		for (String component : unstableComponents()) {
			String[] parts = component.split("#");
			Class<?> record = recordNamed(parts[0]);
			Method accessor = record.getMethod(parts[1]);
			Class<?>[] canonical = Arrays.stream(record.getRecordComponents())
				.map(RecordComponent::getType)
				.toArray(Class<?>[]::new);
			for (java.lang.reflect.Constructor<?> constructor : record.getConstructors()) {
				if (Arrays.equals(constructor.getParameterTypes(), canonical)) {
					continue;
				}
				for (int i = 0; i < constructor.getParameterCount(); i++) {
					if (!constructor.getParameterTypes()[i].isAssignableFrom(Map.class)) {
						continue;
					}
					Object[] args = new Object[constructor.getParameterCount()];
					args[i] = Map.of();
					Object built;
					try {
						built = constructor.newInstance(args);
					}
					catch (ReflectiveOperationException | RuntimeException ex) {
						continue;
					}
					JavaMember member = CLASSES.get(record).getConstructor(constructor.getParameterTypes());
					if (accessor.invoke(built) != null && !isMarked(member)) {
						violations.add(constructor + " sets " + component);
					}
				}
			}
		}
		assertThat(violations).isEmpty();
	}

	@Test
	void negotiatedCapabilitiesForUnstableComponentsAreMarked() {
		Set<String> unstableNames = new TreeSet<>();
		for (String component : unstableComponents()) {
			if (component.startsWith("AgentCapabilities#") || component.startsWith("SessionCapabilities#")) {
				unstableNames.add(component.substring(component.indexOf('#') + 1).toLowerCase());
			}
		}
		assertThat(unstableNames).contains("fork", "providers");
		List<String> violations = new ArrayList<>();
		for (Class<?> type : List.of(NegotiatedCapabilities.class, NegotiatedCapabilities.Builder.class)) {
			for (Method method : type.getDeclaredMethods()) {
				String name = method.getName().toLowerCase();
				if (Modifier.isPublic(method.getModifiers())
						&& unstableNames.stream().anyMatch(name::contains) && !isMarked(member(method))) {
					violations.add(type.getSimpleName() + "." + method.getName());
				}
			}
		}
		assertThat(violations).isEmpty();
	}

	// ---------------------------------------------------------------------------------------

	private static boolean isMarked(JavaClass type) {
		if (type.isAnnotatedWith(UnstableAcpApi.class)) {
			return true;
		}
		return type.getEnclosingClass().map(UnstableApiMarkerTest::isMarked).orElse(false);
	}

	/** A member is unstable when it, or a type enclosing it, is marked. */
	private static boolean isMarked(JavaMember member) {
		return member.isAnnotatedWith(UnstableAcpApi.class) || isMarked(member.getOwner());
	}

	private static JavaMember member(Method method) {
		return CLASSES.get(method.getDeclaringClass()).getMethod(method.getName(), method.getParameterTypes());
	}

	private static boolean isPublicApi(JavaClass type) {
		Class<?> reflected = type.reflect();
		for (Class<?> c = reflected; c != null; c = c.getEnclosingClass()) {
			if (!Modifier.isPublic(c.getModifiers()) && !(c.isMemberClass() && c.getEnclosingClass().isInterface())) {
				return false;
			}
		}
		return !reflected.isAnonymousClass() && !reflected.isLocalClass() && !reflected.isSynthetic();
	}

	private static boolean names(Type type, Set<String> typeNames) {
		if (type instanceof Class<?> c) {
			return c.isArray() ? names(c.getComponentType(), typeNames) : typeNames.contains(c.getName());
		}
		if (type instanceof ParameterizedType p) {
			return names(p.getRawType(), typeNames)
					|| Arrays.stream(p.getActualTypeArguments()).anyMatch(t -> names(t, typeNames));
		}
		if (type instanceof WildcardType w) {
			return Stream.concat(Arrays.stream(w.getUpperBounds()), Arrays.stream(w.getLowerBounds()))
				.anyMatch(t -> names(t, typeNames));
		}
		if (type instanceof GenericArrayType a) {
			return names(a.getGenericComponentType(), typeNames);
		}
		return false;
	}

	/** The builder's {@code xxxHandler(handler)} setters, without the per-name extension ones. */
	private static List<Method> handlerSetters(Class<?> builderType) {
		return Arrays.stream(builderType.getDeclaredMethods())
			.filter(method -> Modifier.isPublic(method.getModifiers()) && method.getName().endsWith("Handler")
					&& method.getParameterCount() == 1 && method.getParameterTypes()[0].isInterface())
			.toList();
	}

	/** The ACP method a setter registers its handler for, read from a fresh builder. */
	private static String registeredMethod(Class<?> builderType, Method setter) throws Exception {
		AcpAgentTransport transport = mock(AcpAgentTransport.class);
		Object builder = builderType == AcpAgent.AsyncAgentBuilder.class ? AcpAgent.async(transport)
				: AcpAgent.sync(transport);
		Class<?> handlerType = setter.getParameterTypes()[0];
		Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[] { handlerType },
				(proxy, method, args) -> null);
		setter.invoke(builder, handler);
		AgentHandlers handlers = agentHandlers(builder);
		List<String> methods = new ArrayList<>();
		handlers.requests().forEach(request -> methods.add(request.method()));
		handlers.notifications().forEach(notification -> methods.add(notification.method()));
		assertThat(methods).as(setter.getName()).hasSize(1);
		return methods.get(0);
	}

	private static AgentHandlers agentHandlers(Object builder) throws ReflectiveOperationException {
		Object asyncBuilder = builder;
		if (builder instanceof AcpAgent.SyncAgentBuilder) {
			Field field = AcpAgent.SyncAgentBuilder.class.getDeclaredField("asyncBuilder");
			field.setAccessible(true);
			asyncBuilder = field.get(builder);
		}
		Field field = AcpAgent.AsyncAgentBuilder.class.getDeclaredField("handlers");
		field.setAccessible(true);
		return (AgentHandlers) field.get(asyncBuilder);
	}

	/** The request type a handler interface takes and the response type it returns. */
	private static List<Class<?>> requestAndResponseTypes(Class<?> handlerType) {
		Method handle = Arrays.stream(handlerType.getMethods())
			.filter(method -> Modifier.isAbstract(method.getModifiers()))
			.findFirst()
			.orElseThrow();
		List<Class<?>> types = new ArrayList<>();
		types.add(handle.getParameterTypes()[0]);
		Type returnType = handle.getGenericReturnType();
		if (returnType instanceof ParameterizedType p) {
			returnType = p.getActualTypeArguments()[0];
		}
		if (returnType instanceof Class<?> c && c != void.class && c != Void.class) {
			types.add(c);
		}
		return types;
	}

	/**
	 * {@code Record#component} for every component of an {@code AcpSchema} capability record
	 * whose JSON property the schema's definition of that record lacks: an agent advertises an
	 * unstable method in its capabilities, so those components serve one.
	 */
	private static Set<String> unstableComponents() {
		Set<String> components = new TreeSet<>();
		JsonNode defs = SCHEMA.get("$defs");
		for (Class<?> nested : AcpSchema.class.getDeclaredClasses()) {
			if (!nested.isRecord() || !nested.getSimpleName().endsWith("Capabilities")
					|| !defs.has(nested.getSimpleName())) {
				continue;
			}
			Set<String> properties = properties(defs.get(nested.getSimpleName()));
			for (RecordComponent component : nested.getRecordComponents()) {
				JsonProperty json = field(nested, component.getName()).getAnnotation(JsonProperty.class);
				String name = json != null ? json.value() : component.getName();
				if (!properties.contains(name)) {
					components.add(nested.getSimpleName() + "#" + component.getName());
				}
			}
		}
		return components;
	}

	/** A definition's property names, with those of the definitions it composes. */
	private static Set<String> properties(JsonNode definition) {
		Set<String> names = new TreeSet<>();
		JsonNode properties = definition.get("properties");
		if (properties != null) {
			properties.fieldNames().forEachRemaining(names::add);
		}
		JsonNode ref = definition.get("$ref");
		if (ref != null) {
			names.addAll(properties(SCHEMA.get("$defs").get(ref.asText().substring("#/$defs/".length()))));
		}
		for (String composition : List.of("allOf", "anyOf", "oneOf")) {
			JsonNode parts = definition.get(composition);
			if (parts != null) {
				parts.forEach(part -> names.addAll(properties(part)));
			}
		}
		return names;
	}

	private static Field field(Class<?> record, String name) {
		try {
			return record.getDeclaredField(name);
		}
		catch (NoSuchFieldException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Class<?> recordNamed(String simpleName) {
		return Arrays.stream(AcpSchema.class.getDeclaredClasses())
			.filter(c -> c.getSimpleName().equals(simpleName))
			.findFirst()
			.orElseThrow();
	}

	private static JsonNode readSchema() {
		try (InputStream in = UnstableApiMarkerTest.class.getResourceAsStream("/schema/v1/schema.json")) {
			return new ObjectMapper().readTree(in);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Set<String> stableMethods() {
		Set<String> methods = new TreeSet<>();
		SCHEMA.findValues("x-method").forEach(node -> methods.add(node.asText()));
		return methods;
	}

	private static Map<String, String> methodConstants() {
		Map<String, String> constants = new LinkedHashMap<>();
		for (Field field : AcpSchema.class.getDeclaredFields()) {
			int modifiers = field.getModifiers();
			if (field.getName().startsWith("METHOD_") && Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
					&& field.getType() == String.class) {
				try {
					constants.put(field.getName(), (String) field.get(null));
				}
				catch (IllegalAccessException ex) {
					throw new IllegalStateException(ex);
				}
			}
		}
		return constants;
	}

	private static Set<String> unstableMethods() {
		Set<String> methods = new TreeSet<>(METHOD_CONSTANTS.values());
		methods.removeAll(STABLE_METHODS);
		return methods;
	}

}
