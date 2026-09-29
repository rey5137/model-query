package com.rey.modelquery.core;

import jakarta.persistence.criteria.Predicate;
import java.util.Optional;

/**
 * One filter recorded by {@link Filters}. A skipped filter is never recorded, so it resolves no join (R-FLT-03);
 * a recorded one resolves its columns only when rendered against a build's {@link JoinContext}.
 */
@FunctionalInterface
interface Filter {

    /** The predicate, or empty when the filter holds for every row, such as {@code notIn(col, List.of())}. */
    Optional<Predicate> toPredicate(JoinContext ctx);
}
