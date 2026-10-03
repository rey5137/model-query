package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * Raised for an {@code MQ4xxx} failure: a configuration or vendor resolution that cannot be used.
 *
 * @implSpec R-ERR-01, R-ERR-05
 */
@Incubating
public class ModelQueryConfigurationException extends ModelQueryException {

    private static final long serialVersionUID = 1L;

    /** Creates an exception whose message is the code followed by {@code detail}. */
    public ModelQueryConfigurationException(MqCode code, String detail) {
        super(code, detail);
    }

    /** Creates an exception whose message is the code followed by {@code detail}, caused by {@code cause}. */
    public ModelQueryConfigurationException(MqCode code, String detail, Throwable cause) {
        super(code, detail, cause);
    }
}
