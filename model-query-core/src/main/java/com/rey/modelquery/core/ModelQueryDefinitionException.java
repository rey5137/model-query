package com.rey.modelquery.core;

/**
 * Raised for an {@code MQ1xxx} failure: a query definition that is invalid when it is built or first resolved.
 *
 * @implSpec R-ERR-01
 */
@Incubating
public class ModelQueryDefinitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final MqCode code;

    /** Creates an exception whose message is the code followed by {@code detail}. */
    public ModelQueryDefinitionException(MqCode code, String detail) {
        super(code.code() + ": " + detail);
        this.code = code;
    }

    /** The code this failure carries. */
    public MqCode code() {
        return code;
    }
}
