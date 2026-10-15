package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;

/**
 * The receiver of a recursive source's step lambda (R-PROC-37, R-SRC-07): {@code step.childKey(...)} names the column
 * a child row is reached by, and the {@link StepBuilder} it returns takes the parent's matching column, so neither
 * argument's role can be swapped without reading wrong. Both columns are of the source model {@code S} and of the
 * same entity type {@code T}, so a pair on two different entities does not compile.
 *
 * <p>The instance is handed to the lambda by {@link SourceQuery.Builder#recursive}, which reads the keys out of it
 * once, where the source is built; nothing here is per-query state (INV-9).
 *
 * @param <S> the source model both keys belong to
 * @implSpec R-PROC-37, R-SRC-07
 */
@Incubating
public final class RecursiveStep<S> {

    /** Built by {@link SourceQuery.Builder#recursive}, which is the only caller. */
    RecursiveStep() {
    }

    /**
     * The column a child row is reached by: the child's foreign key to the parent. The {@link StepBuilder} it returns
     * takes the parent's matching column of the same type.
     */
    public <T> StepBuilder<S, T> childKey(ColumnField<S, T, ?> childKey) {
        return new StepBuilder<>(Objects.requireNonNull(childKey, "childKey"));
    }
}
