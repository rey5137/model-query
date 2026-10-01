package com.rey.modelquery.core;

/**
 * Which page to read: the number of rows to skip and the page size. Immutable; built by {@link #of(int, int)} from a
 * page number or by {@link #ofOffset(int, int)} from an offset, so neither can be mistaken for the other (D-88).
 *
 * @implSpec R-EXE-02, R-EXE-06
 */
public final class PageSpec {

    private final int offset;
    private final int pageSize;

    private PageSpec(int offset, int pageSize) {
        this.offset = offset;
        this.pageSize = pageSize;
    }

    /**
     * The page {@code pageNumber}, counted from zero, of {@code pageSize} rows.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a page size that is not positive, {@code MQ2002} for a
     *     negative page number or one whose offset does not fit an {@code int}
     */
    public static PageSpec of(int pageNumber, int pageSize) {
        requirePositive(pageSize);
        long offset = (long) pageNumber * pageSize;
        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new ModelQueryExecutionException(MqCode.MQ2002, "page " + pageNumber + " of size " + pageSize
                    + " has offset " + offset + ", which is negative or above " + Integer.MAX_VALUE);
        }
        return new PageSpec((int) offset, pageSize);
    }

    /**
     * The {@code pageSize} rows after the first {@code offset}, which need not be a multiple of the page size.
     *
     * @throws ModelQueryExecutionException {@code MQ2001} for a page size that is not positive, {@code MQ2002} for a
     *     negative offset
     */
    public static PageSpec ofOffset(int offset, int pageSize) {
        requirePositive(pageSize);
        if (offset < 0) {
            throw new ModelQueryExecutionException(MqCode.MQ2002, "offset " + offset + " is negative");
        }
        return new PageSpec(offset, pageSize);
    }

    private static void requirePositive(int pageSize) {
        if (pageSize <= 0) {
            throw new ModelQueryExecutionException(MqCode.MQ2001, "pageSize " + pageSize + " is not positive");
        }
    }

    /** The rows skipped before the page, never negative. */
    public int offset() {
        return offset;
    }

    /** The most rows in the page, always positive. */
    public int pageSize() {
        return pageSize;
    }

    /** The page number, counted from zero, that {@link #offset()} falls in. */
    public int pageNumber() {
        return offset / pageSize;
    }

    /** Equal to another {@code PageSpec} with the same offset and page size. */
    @Override
    public boolean equals(Object o) {
        return o instanceof PageSpec other && offset == other.offset && pageSize == other.pageSize;
    }

    @Override
    public int hashCode() {
        return 31 * offset + pageSize;
    }

    @Override
    public String toString() {
        return "PageSpec[offset=" + offset + ", pageSize=" + pageSize + "]";
    }
}
