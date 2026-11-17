package org.example.audit;

import java.util.function.Supplier;

/**
 * An application's call sites, outside the library's packages, for the audit agent's call-site tests.
 */
public final class AuditCaller {

    private AuditCaller() {}

    /** Runs {@code call} simulating a stream application call site. */
    public static <T> T streamCall(Supplier<T> call) {
        return call.get();
    }

    /** Runs {@code call} through an application decorator. */
    public static <T> T appDecoratorCall(Supplier<T> call) {
        return call.get();
    }

    /** Runs {@code call} through a JDK proxy decorator. */
    public static <T> T proxyDecoratorCall(Supplier<T> call) {
        return call.get();
    }

    /** Runs {@code call} for executor methods. */
    public static <T> T executorCall(Supplier<T> call) {
        return call.get();
    }
}
