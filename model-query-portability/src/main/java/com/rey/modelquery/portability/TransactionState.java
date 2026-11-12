package com.rey.modelquery.portability;

import com.rey.modelquery.annotations.Incubating;

/**
 * Whether a recorded call ran in a transaction (R-INS-14).
 *
 * @implSpec R-INS-14
 */
@Incubating
public enum TransactionState {
    /** The call's {@code EntityManager} was joined to a transaction. */
    IN,
    /** The call's {@code EntityManager} was not joined to a transaction. */
    OUTSIDE,
    /** Recorded without an {@code EntityManager}, so the state is not known. */
    UNKNOWN
}
