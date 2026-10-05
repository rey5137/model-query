package com.rey.modelquery.sample.springboot.mysql;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Counts the profile lookup calls, so a test can check the enricher calls it once per chunk (recipe 8). */
@Component
public class ProfileLookupCounter {

    private final AtomicInteger calls = new AtomicInteger();

    /** The calls made since the last {@link #reset()}. */
    public int count() {
        return calls.get();
    }

    /** Forgets the calls made so far. */
    public void reset() {
        calls.set(0);
    }

    void increment() {
        calls.incrementAndGet();
    }
}
