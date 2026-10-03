package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * Raised for an {@code MQ1xxx} failure: a query definition that is invalid when it is built or first resolved.
 *
 * @implSpec R-ERR-01, R-ERR-05
 */
@Incubating
public class ModelQueryDefinitionException extends ModelQueryException {

    private static final long serialVersionUID = 1L;

    /** Creates an exception whose message is the code followed by {@code detail}. */
    public ModelQueryDefinitionException(MqCode code, String detail) {
        super(code, detail);
    }

    /** Creates an exception whose message is the code followed by {@code detail}, caused by {@code cause}. */
    public ModelQueryDefinitionException(MqCode code, String detail, Throwable cause) {
        super(code, detail, cause);
    }
}
