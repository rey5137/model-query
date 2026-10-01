package com.rey.modelquery.core;


/**
 * Raised for an {@code MQ2xxx} failure: a query that is well defined but cannot run the operation asked of it.
 *
 * @implSpec R-ERR-01
 */
public class ModelQueryExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final MqCode code;

    /** Creates an exception whose message is the code followed by {@code detail}. */
    public ModelQueryExecutionException(MqCode code, String detail) {
        super(code.code() + ": " + detail);
        this.code = code;
    }

    /** Creates an exception whose message is the code followed by {@code detail}, caused by {@code cause}. */
    public ModelQueryExecutionException(MqCode code, String detail, Throwable cause) {
        super(code.code() + ": " + detail, cause);
        this.code = code;
    }

    /** The code this failure carries. */
    public MqCode code() {
        return code;
    }
}
