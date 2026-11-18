package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The per-thread current-test stack stays balanced and thread-confined (R-INS-21, AC-INS-22, D-240). */
class AcInsCurrentTestTest {

    @Test
    void ac_ins_22_nested_starts_pop_in_order_and_leave_nothing_behind() {
        String running = CurrentTest.get(); // the real listener is active in this run
        CurrentTest.started("outer", "A", "m");
        CurrentTest.started("inner", "A", "n");
        assertThat(CurrentTest.get()).isEqualTo("A#n");
        CurrentTest.finished("inner");
        assertThat(CurrentTest.get()).isEqualTo("A#m");
        CurrentTest.finished("outer");
        assertThat(CurrentTest.get()).isEqualTo(running);
    }

    @Test
    void ac_ins_22_a_finish_for_a_test_never_started_here_changes_nothing() {
        CurrentTest.started("here", "A", "m");
        CurrentTest.finished("elsewhere");
        assertThat(CurrentTest.get()).isEqualTo("A#m");
        CurrentTest.finished("here");
        assertThat(CurrentTest.get()).isNotEqualTo("A#m");
    }

    @Test
    void ac_ins_22_another_thread_does_not_see_this_threads_test() throws Exception {
        CurrentTest.started("here", "A", "m");
        try { // a plain thread has no stack, whatever this thread runs
            AtomicReference<String> seen = new AtomicReference<>("unset");
            Thread thread = new Thread(() -> seen.set(CurrentTest.get()));
            thread.start();
            thread.join();
            assertThat(seen.get()).isNull();
        } finally {
            CurrentTest.finished("here");
        }
    }
}
