package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;

/**
 * A recursive source's step with its child key named: {@link #parentKey} completes it (R-PROC-37). Both keys share
 * the entity type {@code T}, so a column of another entity does not compile.
 *
 * @param <S> the source model both keys belong to
 * @param <T> the entity both keys sit on
 * @implSpec R-PROC-37, R-SRC-07
 */
@Incubating
public final class StepBuilder<S, T> {

    private final ColumnField<S, T, ?> childKey;

    StepBuilder(ColumnField<S, T, ?> childKey) {
        this.childKey = childKey;
    }

    /** The parent's key column the step matches {@code childKey} against (R-SRC-07). */
    public RecursiveKeys<S, T> parentKey(ColumnField<S, T, ?> parentKey) {
        return new RecursiveKeys<>(childKey, Objects.requireNonNull(parentKey, "parentKey"));
    }
}
