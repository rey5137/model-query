package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;
import java.util.Optional;

/**
 * The enclosing query's vocabulary, handed to the correlation of a correlated {@code exists} so a filter on the
 * sub-select can read a column of the outer root (R-FLT-17). Built by the DSL only; an application never constructs
 * one.
 *
 * <p>{@link #column(ColumnField)} lifts an outer-root column into the sub-select's vocabulary, so it goes wherever a
 * column of {@code S} goes, {@code or} and {@code not} mix inner and outer conditions, and a nested
 * {@code exists(path, ...)} inside the correlation may use it. A lifted column must sit on the outer query's root,
 * else {@code MQ1311}; it is read through a correlation of that root (or of a {@code through} child's join, D-100),
 * so the outer query joins nothing.
 *
 * @param <M> the outer query's model
 * @param <S> the sub-select's inner vocabulary
 * @implSpec api/12 R-FLT-17, api/16 R-INS-06, R-INS-08, D-112
 */
@Incubating
public final class Outer<M, S> {

    /** The root entity of the enclosing query, or {@code null} when the group building this Outer has no root. */
    private final Class<?> rootEntity;

    Outer(Class<?> rootEntity) {
        this.rootEntity = rootEntity;
    }

    /**
     * {@code outerColumn} read from the enclosing query, as a column of the sub-select's vocabulary.
     *
     * @throws ModelQueryDefinitionException {@code MQ1311} when {@code outerColumn} is not on the outer query's root
     * @throws ModelQueryDefinitionException {@code MQ1310} when {@code outerColumn} is itself a lift
     */
    public <T, C> ColumnField<S, T, C> column(ColumnField<M, T, C> outerColumn) {
        Objects.requireNonNull(outerColumn, "outerColumn");
        if (outerColumn.isLifted()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1310,
                    outerColumn + " is a lifted outer column; a column cannot be lifted through two sub-selects");
        }
        // A join below the outer root has a null rootEntity, and R-FLT-17 rejects it for now: Hibernate renders joins
        // off a correlated root as the sub-query's FROM and drops their ON, which would widen a LEFT join to INNER.
        Class<?> columnRoot = outerColumn.table().rootEntity();
        if (columnRoot == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1311, outerColumn
                    + " sits on " + outerColumn.table().describe() + ", not on the outer query's root; lift a root "
                    + "column only");
        }
        if (rootEntity != null && columnRoot != rootEntity) {
            throw new ModelQueryDefinitionException(MqCode.MQ1311, outerColumn
                    + " sits on " + outerColumn.table().describe() + ", not on the outer query's root "
                    + rootEntity.getSimpleName() + "; lift a column of that root only");
        }
        return ColumnField.lifted(outerColumn);
    }

    /**
     * The outer column {@code column} lifts, or empty for a column of a query's own vocabulary (R-INS-08). A test
     * reads it to tell a lifted column from a plain one.
     */
    public static Optional<ColumnField<?, ?, ?>> referenced(SelectField<?, ?> column) {
        return column instanceof ColumnField<?, ?, ?> c ? c.liftedFrom() : Optional.empty();
    }

    /**
     * {@code outerColumn} as the lifted column {@code column(outerColumn)} builds, so a test matcher can name it
     * (@EngineFacing: the {@code model-query-test} matcher {@code outer(col)}, R-INS-06).
     */
    @EngineFacing
    public static <S, T, C> ColumnField<S, T, C> reference(ColumnField<S, T, C> outerColumn) {
        return ColumnField.lifted(Objects.requireNonNull(outerColumn, "outerColumn"));
    }
}
