package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Optional;

/**
 * Renders a column-typed {@link Expr#constant(ColumnField, Object)} as the value its column stores, where the
 * persistence provider can: an {@code @Enumerated(STRING)} constant's name, an {@code ORDINAL} one's ordinal, an
 * {@code AttributeConverter} one's converted database value. Without one, the constant is {@code MQ1508} (R-COL-27).
 *
 * <p>Vendor- and provider-neutral, so {@code core} names no provider (INV-7); {@code model-query-hibernate} supplies
 * one from the attribute's JPA mapping. Immutable and thread-safe.
 *
 * @implSpec R-COL-27, R-VND-16, D-128
 */
@Incubating
@FunctionalInterface
public interface ConstantRenderer {

    /**
     * The value {@code entity}'s attribute {@code attribute} stores for {@code attributeValue}, a {@code String},
     * {@code Number} or {@code Boolean}, or empty when this renderer cannot tell, which is {@code MQ1508} (R-COL-27).
     */
    Optional<Object> storedValue(Class<?> entity, String attribute, Object attributeValue);
}
