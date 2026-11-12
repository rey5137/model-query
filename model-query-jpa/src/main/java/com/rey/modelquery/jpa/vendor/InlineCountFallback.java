package com.rey.modelquery.jpa.vendor;

import com.rey.modelquery.annotations.Incubating;
import java.util.Objects;
import java.util.function.Consumer;

/** The portability dry run's observation of R-EXE-15 fallbacks 1–4 (D-222); not API (D-139). */
@Incubating
public final class InlineCountFallback {

    private static final ThreadLocal<Consumer<Boolean>> OBSERVER = new ThreadLocal<>();

    private InlineCountFallback() {}

    /** Observes inline pages in {@code call} on this thread; restores an enclosing observation even on failure. */
    @Incubating
    public static void observe(Consumer<Boolean> observer, Runnable call) {
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(call, "call");
        Consumer<Boolean> previous = OBSERVER.get();
        OBSERVER.set(observer);
        try {
            call.run();
        } finally {
            if (previous == null) {
                OBSERVER.remove();
            } else {
                OBSERVER.set(previous);
            }
        }
    }

    /** Called by the executor after deciding fallbacks 1–4, before issuing any statement. */
    @Incubating
    public static void decided(boolean fallback) {
        Consumer<Boolean> observer = OBSERVER.get();
        if (observer != null) {
            observer.accept(fallback);
        }
    }
}
