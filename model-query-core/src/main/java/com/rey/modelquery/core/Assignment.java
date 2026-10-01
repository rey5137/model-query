package com.rey.modelquery.core;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * One column an update writes: a model value, NULL, or an expression over the column's path. A value is the model's,
 * not the entity attribute's: the engine applies the column's converter once, when it binds it (D-37). Immutable.
 *
 * @param <M> the model the column belongs to
 * @param <C> the column's Java type
 * @implSpec R-WRT-13, D-60
 */
@Incubating
public sealed interface Assignment<M, C> {

    /** The column written. */
    ColumnField<M, ?, C> column();

    /**
     * Writes {@code value}, which is not {@code null}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1603} for a {@code null} value, which {@link #ofNull} writes
     */
    static <M, C> Assignment<M, C> of(ColumnField<M, ?, C> column, C value) {
        return new Value<>(column, value);
    }

    /** Writes NULL. */
    static <M, C> Assignment<M, C> ofNull(ColumnField<M, ?, C> column) {
        return new Null<>(column);
    }

    /**
     * Writes a model value.
     *
     * @param column the column written
     * @param value the value, never {@code null}
     */
    record Value<M, C>(ColumnField<M, ?, C> column, C value) implements Assignment<M, C> {

        /**
         * Validates the components.
         *
         * @throws ModelQueryDefinitionException {@code MQ1603} for a {@code null} value
         */
        public Value {
            Objects.requireNonNull(column, "column");
            if (value == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1603,
                        column + ": set(...) received null; write NULL with setNull(...) or a change set");
            }
        }
    }

    /**
     * Writes NULL.
     *
     * @param column the column written
     */
    record Null<M, C>(ColumnField<M, ?, C> column) implements Assignment<M, C> {

        /** Validates the component. */
        public Null {
            Objects.requireNonNull(column, "column");
        }
    }

    /**
     * Writes what {@code expression} returns for the column's path, an escape hatch like {@code Agg.of}. The path has
     * the entity attribute's type, so a column with a converter is refused at {@code build()} ({@code MQ1609}).
     *
     * @param column the column written
     * @param expression the value written, given the column's path on the root
     */
    record Expression<M, C>(
            ColumnField<M, ?, C> column,
            BiFunction<Path<C>, CriteriaBuilder, jakarta.persistence.criteria.Expression<? extends C>> expression)
            implements Assignment<M, C> {

        /** Validates the components. */
        public Expression {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(expression, "expression");
        }
    }
}
