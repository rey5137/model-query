package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * Raised for an {@code MQ2xxx} failure: a query that is well defined but cannot run the operation asked of it.
 *
 * @implSpec R-ERR-01, R-ERR-05
 */
@Incubating
public non-sealed class ModelQueryExecutionException extends ModelQueryException {

    private static final long serialVersionUID = 1L;

    /** Creates an exception whose message is the code followed by {@code detail}. */
    public ModelQueryExecutionException(MqCode code, String detail) {
        super(code, detail);
    }

    /** Creates an exception whose message is the code followed by {@code detail}, caused by {@code cause}. */
    public ModelQueryExecutionException(MqCode code, String detail, Throwable cause) {
        super(code, detail, cause);
    }
}
