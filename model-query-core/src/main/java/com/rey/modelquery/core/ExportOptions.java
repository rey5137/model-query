package com.rey.modelquery.core;

import java.util.Objects;

/**
 * How an {@code export} reads: the rows fetched per page, and the most items it passes to the sink. Immutable.
 *
 * @param pageSize the rows fetched per page, always positive
 * @param limit the most items passed to the sink; {@code Limit.of(0)} exports nothing without querying
 * @implSpec R-EXE-06, R-PAG-10
 */
@Incubating
public record ExportOptions(int pageSize, Limit limit) {

    /**
     * Validates the components.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a page size that is not positive
     */
    public ExportOptions {
        if (pageSize <= 0) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "ExportOptions.pageSize " + pageSize
                    + " is not positive");
        }
        Objects.requireNonNull(limit, "limit");
    }

    /**
     * Pages of {@code pageSize} rows, with no limit.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a page size that is not positive
     */
    public static ExportOptions of(int pageSize) {
        return new ExportOptions(pageSize, Limit.unlimited());
    }
}
