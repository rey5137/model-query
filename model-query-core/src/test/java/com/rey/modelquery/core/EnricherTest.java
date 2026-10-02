package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What an enricher does with a page, without a database (spec api/15 R-FCH-08, AC-FCH-06). */
class EnricherTest {

    @Test
    void ac_fch_06_by_key_looks_distinct_keys_up_once_and_leaves_an_absent_or_null_key_as_is() {
        var lookups = new ArrayList<Set<String>>();
        Enricher<String> enricher = Enricher.byKey(s -> s.startsWith("-") ? null : s.substring(0, 1), keys -> {
            lookups.add(keys);
            return Map.of("a", 1, "b", 2);
        }, (s, v) -> s + v);

        assertThat(enricher.enrich("Model", List.of("ax", "-", "by", "cz", "ay")))
                .containsExactly("ax1", "-", "by2", "cz", "ay1");
        assertThat(lookups).hasSize(1);
        assertThat(lookups.get(0)).containsExactly("a", "b", "c");
        // A page without a key calls no lookup.
        assertThat(enricher.enrich("Model", List.of("-", "--"))).containsExactly("-", "--");
        assertThat(lookups).hasSize(1);
    }

    @Test
    void ac_fch_06_of_gets_a_modifiable_copy_and_must_return_the_same_size() {
        List<String> page = List.of("a", "b");
        Enricher<String> upper = Enricher.of(models -> {
            models.replaceAll(String::toUpperCase);
            return models;
        });

        assertThat(upper.enrich("Model", page)).containsExactly("A", "B");
        assertThat(page).containsExactly("a", "b");
        assertThatThrownBy(() -> Enricher.<String>of(models -> Arrays.asList("a", "b", "c")).enrich("Model", page))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2602))
                .hasMessage("MQ2602: Model: an Enricher.of returned a page of 3 for a page of 2 models; return one "
                        + "model per model of the page, filled");
    }

    @Test
    void ac_fch_06_of_rejects_a_null_model_in_the_result() {
        List<String> page = List.of("a", "b");

        assertThatThrownBy(() -> Enricher.<String>of(models -> Arrays.asList("a", null)).enrich("Model", page))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2602))
                .hasMessage("MQ2602: Model: an Enricher.of returned null at position 1 of the page; return one "
                        + "model per model of the page, filled");
    }

    @Test
    void ac_fch_06_of_accepts_an_immutable_result_list_without_a_null() {
        List<String> page = List.of("a", "b");

        assertThat(Enricher.<String>of(models -> List.copyOf(models)).enrich("Model", page)).containsExactly("a", "b");
        assertThat(Enricher.<String>of(models -> List.of("x", "y")).enrich("Model", page)).containsExactly("x", "y");
    }
}
