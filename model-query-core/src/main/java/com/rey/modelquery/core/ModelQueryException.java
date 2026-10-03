package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;

/**
 * Every failure the library raises: a {@link ModelQueryDefinitionException}, a {@link ModelQueryExecutionException}
 * or a {@link ModelQueryConfigurationException}. Catching this type catches all three, and {@link #code()} says which
 * failure it was. The one failure outside it is the JPA {@code OptimisticLockException} an {@code expectVersion}
 * update throws when it affects no rows (R-ERR-04).
 *
 * @implSpec R-ERR-01, R-ERR-05, D-106
 */
@Incubating
public abstract class ModelQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final MqCode code;

    /** Creates an exception whose message is the code followed by {@code detail}. */
    protected ModelQueryException(MqCode code, String detail) {
        super(code.code() + ": " + detail);
        this.code = code;
    }

    /** Creates an exception whose message is the code followed by {@code detail}, caused by {@code cause}. */
    protected ModelQueryException(MqCode code, String detail, Throwable cause) {
        super(code.code() + ": " + detail, cause);
        this.code = code;
    }

    /** The code this failure carries. */
    public MqCode code() {
        return code;
    }
}
