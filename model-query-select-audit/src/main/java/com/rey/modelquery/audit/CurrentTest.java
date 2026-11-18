package com.rey.modelquery.audit;

import java.util.ArrayDeque;
import java.util.Deque;

/** The test method each thread is executing, kept by the JUnit listener and read by {@link CallSiteCapture}. */
final class CurrentTest {
    private record Entry(String id, String name) {}

    private static final ThreadLocal<Deque<Entry>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private CurrentTest() {}

    static void started(String uniqueId, String className, String method) {
        STACK.get().push(new Entry(uniqueId, className + "#" + method));
    }

    static void finished(String uniqueId) {
        Deque<Entry> stack = STACK.get();
        if (!stack.isEmpty() && stack.peek().id().equals(uniqueId)) {
            stack.pop();
            if (stack.isEmpty()) {
                STACK.remove();
            }
        }
    }

    /** Returns {@code class#method} of the test running on this thread, or {@code null} outside one. */
    static String get() {
        Deque<Entry> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek().name();
    }
}
