package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds a model from a complete {@link Row} in one call, so classes and records use the same engine (R-COL-11).
 *
 * @param <M> the model type
 * @implSpec R-COL-10, R-COL-11
 */
@Incubating
@FunctionalInterface
public interface RowMapper<M> {

    /** Builds one model from {@code row}. */
    M map(Row row);

    /** A mapper that creates a model with {@code factory} and fills it through bound setters. */
    static <M> SetterMapper<M> setters(Supplier<M> factory) {
        return SetterMapper.of(Objects.requireNonNull(factory, "factory"));
    }
}
