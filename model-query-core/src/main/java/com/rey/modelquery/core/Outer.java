package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;
import java.util.Optional;

/**
 * The enclosing query's vocabulary, handed to the correlation of a correlated {@code exists} so a filter on the
 * sub-select can read a column of the outer root or a to-one path (R-FLT-17). Built by the DSL only; an application
 * never constructs one.
 *
 * <p>{@link #column(ColumnField)} lifts an outer column into the sub-select's vocabulary, so it goes wherever a
 * column of {@code S} goes, {@code or} and {@code not} mix inner and outer conditions, and a nested
 * {@code exists(path, ...)} inside the correlation may use it. A lifted column may sit on the outer root or a to-one
 * path below it; a path crossing a to-many join raises {@code MQ1311} at first resolution. The outer context resolves
 * the path, and the sub-query correlates that root or join (R-FLT-27, D-150).
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
     * @throws ModelQueryDefinitionException {@code MQ1311} when {@code outerColumn}'s path starts at another root
     * @throws ModelQueryDefinitionException {@code MQ1310} when {@code outerColumn} is itself a lift
     */
    public <T, C> ColumnField<S, T, C> column(ColumnField<M, T, C> outerColumn) {
        Objects.requireNonNull(outerColumn, "outerColumn");
        checkLiftable(outerColumn, rootEntity);
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
     * (@EngineFacing: the {@code model-query-test} matcher {@code outer(col)}, R-INS-06). It refuses the columns
     * {@link #column(ColumnField)} refuses without an enclosing root, MQ1310; cardinality is checked at resolution.
     *
     * @throws ModelQueryDefinitionException {@code MQ1310} when {@code outerColumn} is itself a lift
     */
    @EngineFacing
    public static <S, T, C> ColumnField<S, T, C> reference(ColumnField<S, T, C> outerColumn) {
        Objects.requireNonNull(outerColumn, "outerColumn");
        checkLiftable(outerColumn, null);
        return ColumnField.lifted(outerColumn);
    }

    /**
     * Refuses a lift of a lift (MQ1310) or a path starting at another root (MQ1311). Cardinality is checked at first
     * resolution (R-FLT-27); {@code rootEntity} is null when the caller has no enclosing root to check.
     */
    private static void checkLiftable(ColumnField<?, ?, ?> outerColumn, Class<?> rootEntity) {
        if (outerColumn.isLifted()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1310,
                    outerColumn + " is a lifted outer column; a column cannot be lifted through two sub-selects");
        }
        Class<?> columnRoot = outerColumn.table().pathRoot();
        if (rootEntity != null && columnRoot != rootEntity) {
            throw new ModelQueryDefinitionException(MqCode.MQ1311, outerColumn
                    + " sits on " + outerColumn.table().describe() + ", not on the outer query's root "
                    + rootEntity.getSimpleName() + "; lift a column of that root or a to-one path below it");
        }
    }
}
