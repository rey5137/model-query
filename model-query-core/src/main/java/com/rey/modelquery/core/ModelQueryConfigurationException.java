package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * Raised for an {@code MQ4xxx} failure: a configuration or vendor resolution that cannot be used.
 *
 * @implSpec R-ERR-01
 */
@Incubating
public class ModelQueryConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final MqCode code;

    /** Creates an exception whose message is the code followed by {@code detail}. */
    public ModelQueryConfigurationException(MqCode code, String detail) {
        super(code.code() + ": " + detail);
        this.code = code;
    }

    /** Creates an exception whose message is the code followed by {@code detail}, caused by {@code cause}. */
    public ModelQueryConfigurationException(MqCode code, String detail, Throwable cause) {
        super(code.code() + ": " + detail, cause);
        this.code = code;
    }

    /** The code this failure carries. */
    public MqCode code() {
        return code;
    }
}
