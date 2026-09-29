package com.rey.modelquery.core;

/**
 * Which page to read: the number of rows to skip and the page size. Immutable.
 *
 * @param offset the rows skipped before the page, never negative
 * @param pageSize the most rows in the page, always positive
 * @implSpec R-EXE-02, R-EXE-06
 */
@Incubating
public record PageSpec(int offset, int pageSize) {

    /**
     * Validates the components.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a page size that is not positive, {@code MQ2002} for a
     *     negative offset
     */
    public PageSpec {
        if (pageSize <= 0) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "pageSize " + pageSize + " is not positive");
        }
        if (offset < 0) {
            throw new ModelQueryExecutionException(MqCode.MQ2002, "offset " + offset + " is negative");
        }
    }

    /**
     * The page {@code pageNumber}, counted from zero, of {@code pageSize} rows.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a page size that is not positive, {@code MQ2002} for a
     *     negative page number or one whose offset does not fit an {@code int}
     */
    public static PageSpec of(int pageNumber, int pageSize) {
        if (pageSize <= 0) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "pageSize " + pageSize + " is not positive");
        }
        long offset = (long) pageNumber * pageSize;
        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new ModelQueryExecutionException(MqCode.MQ2002, "page " + pageNumber + " of size " + pageSize
                    + " has offset " + offset + ", which is negative or above " + Integer.MAX_VALUE);
        }
        return new PageSpec((int) offset, pageSize);
    }

    /** The page number, counted from zero, that {@link #offset()} falls in. */
    public int pageNumber() {
        return offset / pageSize;
    }
}
