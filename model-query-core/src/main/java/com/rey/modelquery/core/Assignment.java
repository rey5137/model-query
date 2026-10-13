package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * One column a write writes: a model value, NULL, an expression over the column's path, or one that reads no column
 * (R-WRT-53). A value is the model's, not the entity attribute's: the engine applies the column's converter once,
 * when it binds it (D-37). Immutable. The permits grow while it is incubating, so an exhaustive {@code switch} over it
 * may need another case.
 *
 * @param <M> the model the column belongs to
 * @param <C> the column's Java type
 * @implSpec R-WRT-13, R-WRT-53, D-60
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

    /**
     * Writes what a {@link ConflictExpression} returns for the conflicted column, given the stored and the incoming
     * row (R-WRT-51). Only a conflict update offers one: it is not among the {@code ModelUpdate} assignments, whose
     * plain {@link Expression} takes a single path. The column has the entity attribute's type, so a converter is
     * refused at {@code build()} ({@code MQ1609}).
     *
     * @param column the column written
     * @param expression the value written, given the stored and the incoming row's paths
     */
    record Conflict<M, C>(ColumnField<M, ?, C> column, ConflictExpression<C> expression) implements Assignment<M, C> {

        /** Validates the components. */
        public Conflict {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(expression, "expression");
        }
    }

    /**
     * Writes {@code value}, an expression that reads no column and binds nothing, such as {@code Expr.nextval},
     * evaluated per written row (R-WRT-53). Every write takes one: an update, a conflict update and an insert. The
     * column has the entity attribute's type, so a converter is refused at {@code build()} ({@code MQ1609}).
     *
     * @param column the column written
     * @param value the value written
     */
    record Computed<M, C>(ColumnField<M, ?, C> column, ExpressionField<?, ? extends C> value)
            implements Assignment<M, C> {

        /**
         * Validates the components.
         *
         * @throws ModelQueryDefinitionException {@code MQ1613} for an expression that reads a column or binds a
         *     parameter: it renders where no row of the root is in scope, such as an insert's {@code VALUES}
         */
        public Computed {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(value, "value");
            if (!value.columns().isEmpty() || value.binds() != 0) {
                throw new ModelQueryDefinitionException(MqCode.MQ1613, column + ": setExpression(..., " + value.name()
                        + ") " + (value.columns().isEmpty() ? "binds a parameter" : "reads a column")
                        + "; a write expression reads no column and binds nothing, such as Expr.nextval, with "
                        + "Expr.constant for a fixed operand");
            }
        }
    }
}
