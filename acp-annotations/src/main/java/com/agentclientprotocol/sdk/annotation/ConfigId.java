/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code String} parameter of a {@link SetSessionConfigOption} method to receive the
 * id of the config option the client is setting ({@code configId}).
 *
 * @author Mark Pollack
 * @since 0.80.0
 * @see SetSessionConfigOption
 * @see ConfigValue
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConfigId {

}
