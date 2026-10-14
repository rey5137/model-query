package com.rey.modelquery.portability.internal;

import com.rey.modelquery.annotations.Incubating;

/**
 * How one call ended on one target, read from its JDBC recording first and its cause chain second, never from the
 * top-level exception type.
 *
 * @implSpec R-INS-11
 */
@Incubating
public enum Outcome {
    /** A statement was reached and no refusal was met: the refusals before it, and its rendering, passed. */
    REACHED,
    /** A refusal before any statement: the target is unserved. */
    REFUSED,
    /** A refusal after a statement was reached: an INV-5 or spec bug, or a call that swallowed the stub's stop. */
    REFUSED_AFTER_STATEMENT,
    /** The call returned having reached no statement, so nothing was checked. */
    NO_STATEMENT,
    /** Anything else: another throwable, or JDBC reached other than through a statement. */
    HARNESS_ERROR
}
