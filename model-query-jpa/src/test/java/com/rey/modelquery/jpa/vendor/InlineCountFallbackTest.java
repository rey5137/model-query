package com.rey.modelquery.jpa.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class InlineCountFallbackTest {

    @Test
    void ac_ins_13_observation_is_scoped_and_restored_after_nested_failure() {
        List<Boolean> outer = new ArrayList<>();
        List<Boolean> inner = new ArrayList<>();
        RuntimeException failure = new RuntimeException("stop");
        InlineCountFallback.observe(outer::add, () -> {
            InlineCountFallback.decided(false);
            assertThatThrownBy(() -> InlineCountFallback.observe(inner::add, () -> {
                InlineCountFallback.decided(true);
                throw failure;
            })).isSameAs(failure);
            InlineCountFallback.decided(true);
        });
        InlineCountFallback.decided(false);
        assertThat(outer).containsExactly(false, true);
        assertThat(inner).containsExactly(true);
        assertThatThrownBy(() -> InlineCountFallback.observe(inner::add, () -> {
            throw failure;
        })).isSameAs(failure);
        InlineCountFallback.decided(false);
        assertThat(inner).containsExactly(true);
        InlineCountFallback.observe(inner::add, () -> InlineCountFallback.decided(false));
        assertThat(inner).containsExactly(true, false);
    }

    @Test
    void ac_ins_13_observation_does_not_cross_threads() {
        List<Boolean> decisions = new ArrayList<>();
        InlineCountFallback.observe(decisions::add,
                () -> CompletableFuture.runAsync(() -> InlineCountFallback.decided(true)).join());
        assertThat(decisions).isEmpty();
    }
}
