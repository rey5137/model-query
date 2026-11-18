package com.rey.modelquery.audit;

import com.rey.modelquery.annotations.Incubating;

/** A model property was read although its query did not select it (R-INS-20). */
@Incubating
public final class UnselectedColumnRead extends AssertionError {
    /** Creates an error describing the read and its remedy. */
    @Incubating
    public UnselectedColumnRead(String message) {
        super(message);
    }
}
