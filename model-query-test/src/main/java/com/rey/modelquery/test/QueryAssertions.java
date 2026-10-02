package com.rey.modelquery.test;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQuery;

/**
 * The entry point: {@code assertThatQuery(query).hasFilters(eq(COLUMN, value))} (api/16 §1).
 *
 * @implSpec api/16 R-INS-06
 */
@Incubating
public final class QueryAssertions {

    private QueryAssertions() {}

    /** Assertions on what {@code actual} filters on, orders by and selects. */
    public static <M> QueryAssert<M> assertThatQuery(ModelQuery<?, ?, M> actual) {
        return new QueryAssert<>(actual);
    }
}
