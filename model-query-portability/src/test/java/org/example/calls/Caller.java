package org.example.calls;

import java.util.function.Supplier;

/** An application's call sites, outside the library's packages, for the recorder's call-site tests. */
public final class Caller {

    private Caller() {}

    /** Runs {@code call} from this method's frame. */
    public static <R> R search(Supplier<R> call) {
        return call.get();
    }

    /** Runs {@code call} from a second frame. */
    public static <R> R report(Supplier<R> call) {
        return call.get();
    }

    /** This file's line of {@code search}'s call, for the expected call site. */
    public static int searchLine() {
        return 12;
    }

    /** This file's line of {@code report}'s call. */
    public static int reportLine() {
        return 17;
    }
}
