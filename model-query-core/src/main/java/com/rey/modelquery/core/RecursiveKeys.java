package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;

/**
 * A recursive source's step, both keys named (R-PROC-37, R-SRC-07): a row is a child of another when its
 * {@link #childKey()} equals the other row's {@link #parentKey()}. The source's CTE joins each pair, and recurses
 * from the seed rows until {@code maxDepth} levels past them.
 *
 * @param <S> the source model both keys belong to
 * @param <T> the entity both keys sit on
 * @implSpec R-PROC-37, R-SRC-07
 */
@Incubating
public record RecursiveKeys<S, T>(ColumnField<S, T, ?> childKey, ColumnField<S, T, ?> parentKey) {

    /** Validates the keys. */
    public RecursiveKeys {
        Objects.requireNonNull(childKey, "childKey");
        Objects.requireNonNull(parentKey, "parentKey");
    }
}
