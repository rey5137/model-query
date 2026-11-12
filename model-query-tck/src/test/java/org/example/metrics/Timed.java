package org.example.metrics;

import java.util.function.Supplier;

/**
 * A third-party helper such as a metrics timer: a decorator delegating through it makes its frame, not the
 * application's, the first outside the library (R-INS-14).
 */
public final class Timed {

    private Timed() {}

    /** The result of {@code body}, as a timer's {@code record} would time it. */
    public static <T> T call(Supplier<T> body) {
        return body.get();
    }
}
