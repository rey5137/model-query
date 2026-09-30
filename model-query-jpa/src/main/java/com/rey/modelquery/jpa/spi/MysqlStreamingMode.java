package com.rey.modelquery.jpa.spi;

import com.rey.modelquery.core.Incubating;

/**
 * How the MySQL profile streams a large result (R-PRF-07). Read once when the profile is chosen, never per query.
 *
 * @implSpec R-PRF-07, R-PRF-04
 */
@Incubating
public enum MysqlStreamingMode {

    /**
     * The default: Connector/J streams one row at a time ({@code fetchSize = Integer.MIN_VALUE}). The connection
     * cannot run another statement until the whole result has been read (R-PRF-04).
     */
    ROW_BY_ROW,

    /**
     * A server-side cursor with a positive fetch size: the connection stays usable between fetches. Connector/J
     * ignores the fetch size unless the JDBC URL carries {@code useCursorFetch=true}, which the library cannot set.
     */
    CURSOR_FETCH
}
