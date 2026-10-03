/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an element of this SDK that maps to a part of ACP defined only in the protocol's unstable
 * schema ({@code schema.unstable.json}), such as session forking and the provider methods. Such
 * elements work, but can change or be removed in any release, as the protocol changes them. An
 * element without this annotation is a stable part of the API.
 *
 * <p>When the protocol moves the element into its stable schema ({@code schema.json}), the SDK
 * removes the annotation; removing it is a compatible change. "Unstable" refers to the protocol's
 * stability, not to the quality of the implementation.
 *
 * <p>What it covers: on a package, the package's types, but not its subpackages; on a type, the
 * type's members, but not its subtypes; on a method, that method only, not the methods that
 * override it.
 *
 * <p>The annotation is kept in class files but not at run time, so reflection cannot see it. It is
 * a marker for readers and tools, not access control: IntelliJ IDEA's
 * <em>Unstable API Usage</em> inspection can be set up to flag code that uses annotated elements.
 *
 * @author Mark Pollack
 * @since 0.12.0
 * @see <a href="https://agentclientprotocol.com/protocol/overview">ACP Protocol</a>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD,
		ElementType.PACKAGE, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE })
public @interface UnstableAcpApi {

	/**
	 * A link to, or an identifier of, the protocol proposal, specification entry or issue that
	 * the element's move to the stable schema depends on.
	 * @return the link or identifier, empty by default
	 */
	String value() default "";

}
