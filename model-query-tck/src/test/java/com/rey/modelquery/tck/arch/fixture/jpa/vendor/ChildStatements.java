package com.rey.modelquery.tck.arch.fixture.jpa.vendor;

/** Fixture for D-224's member-level allow-list. */
public final class ChildStatements {
    public static void run(Runnable call) {
        call.run();
    }

    /** The executor's side, which only the engine calls. */
    public interface Runner {
        void runChildLoad();
    }
}
