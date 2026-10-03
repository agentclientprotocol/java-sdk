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
 * Marks a parameter of a {@link SetSessionConfigOption} method to receive the value the client
 * is setting, typed by the parameter:
 * <ul>
 *   <li>{@code String} - the value id of a {@code select} option</li>
 *   <li>{@code boolean} or {@code Boolean} - the value of a {@code boolean} option</li>
 *   <li>{@code Object} - the value as it arrived, whichever kind of option it is</li>
 * </ul>
 * A value of the other kind (a boolean for a {@code String} parameter, or a select value for a
 * {@code boolean} one) is answered with error {@code -32602} (invalid params) without calling
 * the method. Whether the id and value name an option and a value the session offers is the
 * method's to check.
 *
 * @author Mark Pollack
 * @since 0.80.0
 * @see SetSessionConfigOption
 * @see ConfigId
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConfigValue {

}
