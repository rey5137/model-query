package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;

/**
 * A recursive source's step with its child key named: {@link #parentKey} completes it (R-PROC-37). Both keys share
 * the key type {@code C}, so a pair of different types does not compile.
 *
 * @param <S> the source model both keys belong to
 * @param <C> the key type both columns share
 * @implSpec R-PROC-37, R-SRC-07
 */
@Incubating
public final class StepBuilder<S, C> {

    private final ColumnField<S, ?, C> childKey;

    StepBuilder(ColumnField<S, ?, C> childKey) {
        this.childKey = childKey;
    }

    /** The parent's key column the step matches {@code childKey} against (R-SRC-07). */
    public RecursiveKeys<S, C> parentKey(ColumnField<S, ?, C> parentKey) {
        return new RecursiveKeys<>(childKey, Objects.requireNonNull(parentKey, "parentKey"));
    }
}
