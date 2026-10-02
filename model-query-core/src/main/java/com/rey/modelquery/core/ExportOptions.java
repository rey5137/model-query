package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * How an {@code export} reads: the rows fetched per page, and the most items it passes to the sink. Immutable; built
 * by {@link #defaults()} or {@link #of(int)} and narrowed by {@link #withLimit(Limit)}, so it can gain an option
 * without breaking a caller (D-88).
 *
 * @implSpec R-EXE-06, R-PAG-10, R-QRY-15
 */
@Incubating
public final class ExportOptions {

    private static final ExportOptions DEFAULTS = new ExportOptions(OptionalInt.empty(), Limit.unlimited());

    private final OptionalInt pageSize;
    private final Limit limit;

    private ExportOptions(OptionalInt pageSize, Limit limit) {
        this.pageSize = pageSize;
        this.limit = Objects.requireNonNull(limit, "limit");
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
        if (pageSize <= 0) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "ExportOptions.pageSize " + pageSize
                    + " is not positive");
        }
        return new ExportOptions(OptionalInt.of(pageSize), Limit.unlimited());
    }

    /** A copy that passes at most {@code limit} items to the sink; {@code Limit.of(0)} exports nothing. */
    public ExportOptions withLimit(Limit limit) {
        return new ExportOptions(pageSize, limit);
    }

    /** The rows fetched per page, positive when present; empty leaves it to the executor's configured default. */
    public OptionalInt pageSize() {
        return pageSize;
    }

    /** The most items passed to the sink; {@code Limit.of(0)} exports nothing without querying. */
    public Limit limit() {
        return limit;
    }

    /** Equal to another {@code ExportOptions} with the same page size and limit. */
    @Override
    public boolean equals(Object o) {
        return o instanceof ExportOptions other && pageSize.equals(other.pageSize) && limit.equals(other.limit);
    }

    @Override
    public int hashCode() {
        return 31 * pageSize.hashCode() + limit.hashCode();
    }

    @Override
    public String toString() {
        return "ExportOptions[pageSize=" + pageSize + ", limit=" + limit + "]";
    }
}
