package com.rey.modelquery.tck.arch.fixture.jpa.vendor;

/** Fixture for D-222's member-level allow-list. */
public final class InlineCountFallback {
    public static void observe(Runnable call) {
        call.run();
    }

    public static void decided(boolean fallback) {}
}
