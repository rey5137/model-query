package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;

/**
 * A named, typed placeholder for a request value an expression reads through {@link Expr#param}, bound per built query
 * by {@link ModelQuery.Builder#bind} or {@link SourceQuery.Builder#bind} (R-COL-40). It holds no value: the value lives
 * on the built query, so a param may be a {@code static final} constant shared by any number of queries (INV-9).
 *
 * <p>Equal by identity: two params with one name are two parameters, and a query that reads both is {@code MQ1512}.
 *
 * @param <C> the value's Java type
 * @implSpec api/10 R-COL-40, D-198
 */
@Incubating
public final class QueryParam<C> {

    private final String name;
    private final Class<C> type;

    private QueryParam(String name, Class<C> type) {
        this.name = name;
        this.type = type;
    }

    /**
     * A param named {@code name} of {@code type}; a primitive type is its wrapper. Which types bind is decided where
     * the param is read, by {@link Expr#param} (R-COL-18).
     *
     * @throws IllegalArgumentException when {@code name} is blank
     */
    @SuppressWarnings("unchecked")
    public static <C> QueryParam<C> of(String name, Class<C> type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a query param needs a name");
        }
        return new QueryParam<>(name, (Class<C>) ColumnField.boxed(type));
    }

    /** The name messages and inspection show the param by. */
    public String name() {
        return name;
    }

    /** The value's type, a wrapper for a primitive. */
    public Class<C> type() {
        return type;
    }

    /** The name and type, never a value (R-INS-05). */
    @Override
    public String toString() {
        return name + ": " + type.getSimpleName();
    }
}
