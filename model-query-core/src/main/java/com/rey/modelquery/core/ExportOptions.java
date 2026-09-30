package com.rey.modelquery.core;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * How an {@code export} reads: the rows fetched per page, and the most items it passes to the sink. Immutable.
 *
 * @param pageSize the rows fetched per page, positive when present; empty leaves it to the executor's configured
 *     default
 * @param limit the most items passed to the sink; {@code Limit.of(0)} exports nothing without querying
 * @implSpec R-EXE-06, R-PAG-10, R-QRY-15
 */
@Incubating
public record ExportOptions(OptionalInt pageSize, Limit limit) {

    private static final ExportOptions DEFAULTS = new ExportOptions(OptionalInt.empty(), Limit.unlimited());

    /**
     * Validates the components.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a page size that is not positive
     */
    public ExportOptions {
        Objects.requireNonNull(pageSize, "pageSize");
        if (pageSize.isPresent() && pageSize.getAsInt() <= 0) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "ExportOptions.pageSize " + pageSize.getAsInt()
                    + " is not positive");
        }
        Objects.requireNonNull(limit, "limit");
    }

    /** The executor's configured page size, with no limit (D-53). */
    public static ExportOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Pages of {@code pageSize} rows, with no limit.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a page size that is not positive
     */
    public static ExportOptions of(int pageSize) {
        return new ExportOptions(OptionalInt.of(pageSize), Limit.unlimited());
    }

    /** A copy that passes at most {@code limit} items to the sink. */
    public ExportOptions withLimit(Limit limit) {
        return new ExportOptions(pageSize, limit);
    }
}
