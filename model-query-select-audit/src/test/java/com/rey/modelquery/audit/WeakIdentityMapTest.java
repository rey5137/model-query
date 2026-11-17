package com.rey.modelquery.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.BitSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeakIdentityMapTest {

    @Test
    void weakly_held_models_are_expunged_on_gc() {
        WeakIdentityMap<Object, Tag> map = new WeakIdentityMap<>();
        Object model = new Object();
        Tag tag = new Tag("Site.method:1", "list", null, new BitSet(), Map.of());

        map.put(model, tag);
        assertThat(map.get(model)).isSameAs(tag);
        assertThat(map.size()).isEqualTo(1);

        // Clear strong reference
        model = null;
        for (int i = 0; i < 10 && map.size() > 0; i++) {
            System.gc();
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {}
        }

        assertThat(map.size()).isEqualTo(0);
    }
}
