package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** What an enricher does with a page, without a database (spec api/15 R-FCH-08, R-FCH-15, AC-FCH-06). */
class EnricherTest {

    /** A model with two keys and no setters, so {@code with} has to copy: {@code record}s are immutable. */
    record Actor(String payer, String payee) {
        Actor withPayer(String value) {
            return new Actor(value, payee);
        }

        Actor withPayee(String value) {
            return new Actor(payer, value);
        }
    }

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

    // ---- AC-FCH-14

    @Test
    void ac_fch_14_by_keys_looks_distinct_non_null_keys_up_once_and_routes_each_value_to_its_own_key() {
        var lookups = new ArrayList<Set<String>>();
        var profiles = new HashMap<String, String>();
        profiles.put("u1", "Ada");
        profiles.put("u2", "Bob");
        profiles.put("u4", null);
        Enricher<Actor> actors = Enricher.<Actor, String, String>byKeys(keys -> {
            lookups.add(keys);
            return profiles;
        }).key(Actor::payer, Actor::withPayer).key(Actor::payee, Actor::withPayee).reading();

        List<Actor> page = List.of(new Actor("u1", "u2"), new Actor("u2", null), new Actor(null, "u4"),
                new Actor("u1", "u1"));

        // u2 is a payer and a payee, u1 fills both fields of the last model; u4's null value leaves it as is.
        assertThat(actors.enrich("Actor", page)).containsExactly(new Actor("Ada", "Bob"), new Actor("Bob", null),
                new Actor(null, "u4"), new Actor("Ada", "Ada"));
        // One call, with the distinct non-null keys in first-seen order, as an unmodifiable set.
        assertThat(lookups).hasSize(1);
        assertThat(lookups.get(0)).containsExactly("u1", "u2", "u4");
        assertThatThrownBy(() -> lookups.get(0).add("u5")).isInstanceOf(UnsupportedOperationException.class);
        // A page without a key calls no lookup.
        assertThat(actors.enrich("Actor", List.of(new Actor(null, null)))).containsExactly(new Actor(null, null));
        assertThat(lookups).hasSize(1);
    }

    @Test
    void ac_fch_14_a_with_returning_null_throws_mq2602() {
        // A3: a with() that returns null is refused like Enricher.of's null model (api/15 R-FCH-14, R-FCH-15).
        Enricher<Actor> actors = Enricher.<Actor, String, String>byKeys(keys -> Map.of("u1", "Ada"))
                .key(Actor::payer, (actor, value) -> null)
                .reading();

        assertThatThrownBy(() -> actors.enrich("Actor", List.of(new Actor("u1", null))))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2602))
                .hasMessage("MQ2602: Actor: an Enricher.byKey or byKeys with() returned null at key index 0; return "
                        + "the model it was given, filled");
    }

    @Test
    void ac_fch_14_a_key_two_bindings_share_is_read_once_per_binding_from_the_model_the_page_holds() {
        // Two bindings read the payer, and the first with() overwrites it: the second still reads the page's payer.
        var reads = new ArrayList<String>();
        Function<Actor, String> payer = actor -> {
            reads.add(actor.payer());
            return actor.payer();
        };
        Enricher<Actor> actors = Enricher.<Actor, String, String>byKeys(keys -> Map.of("k1", "k2", "k2", "K2"))
                .key(payer, Actor::withPayer).key(payer, Actor::withPayee).reading();

        List<Actor> page = List.of(new Actor("k1", null));

        // Both fields get k1's value, k2, though withPayer put k2 in the payer before withPayee ran.
        assertThat(actors.enrich("Actor", page)).containsExactly(new Actor("k2", "k2"));
        // The key ran once per binding, both from the model as the page held it.
        assertThat(reads).containsExactly("k1", "k1");
    }

    // ---- AC-FCH-15

    @Test
    void ac_fch_15_by_key_is_the_one_key_case_of_by_keys() {
        var byKeyLookups = new ArrayList<Set<String>>();
        Enricher<Actor> one = Enricher.byKey(Actor::payer, keys -> {
            byKeyLookups.add(keys);
            return Map.of("u1", "Ada", "u2", "Bob");
        }, Actor::withPayer);
        var byKeysLookups = new ArrayList<Set<String>>();
        Enricher<Actor> many = Enricher.<Actor, String, String>byKeys(keys -> {
            byKeysLookups.add(keys);
            return Map.of("u1", "Ada", "u2", "Bob");
        }).key(Actor::payer, Actor::withPayer).reading();

        List<Actor> page = List.of(new Actor("u1", "u2"), new Actor(null, null), new Actor("u2", "u1"));

        assertThat(one.enrich("Actor", page)).isEqualTo(many.enrich("Actor", page));
        assertThat(byKeyLookups).isEqualTo(byKeysLookups);
    }

    @Test
    void ac_fch_15_reading_with_no_key_throws_mq1706() {
        assertThatThrownBy(() -> Enricher.<Actor, String, String>byKeys(keys -> Map.of()).reading())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1706))
                .hasMessage("MQ1706: Enricher.byKeys(...).reading(...) declares no key(...); add one key(key, with) "
                        + "per field the enricher fills");
    }

    @Test
    void ac_fch_15_batch_size_below_one_throws_mq1707() {
        assertThatThrownBy(() -> Enricher.<Actor, String, String>byKeys(keys -> Map.of()).batchSize(0))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1707));
    }

    @Test
    void ac_fch_15_a_null_lookup_result_throws_mq2606_for_by_key_and_by_keys() {
        var page = List.of(new Actor("u1", null));

        assertThatThrownBy(() -> Enricher.byKey(Actor::payer, keys -> null, Actor::withPayer).enrich("Actor", page))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2606));
        assertThatThrownBy(() -> Enricher.<Actor, String, String>byKeys(keys -> null)
                .key(Actor::payer, Actor::withPayer).reading().enrich("Actor", page))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2606));
    }

    // ---- AC-FCH-16

    @Test
    void ac_fch_16_batch_size_splits_the_distinct_keys_into_consecutive_chunks_in_first_seen_order() {
        var chunks = new ArrayList<Set<String>>();
        Enricher<Actor> actors = Enricher.<Actor, String, String>byKeys(keys -> {
            chunks.add(keys);
            return Map.of();
        }).key(Actor::payer, Actor::withPayer).batchSize(2).reading();

        List<Actor> page = List.of(new Actor("k1", null), new Actor("k2", null), new Actor("k3", null),
                new Actor("k4", null), new Actor("k5", null), new Actor("k1", null), new Actor(null, null));

        actors.enrich("Actor", page);

        // ceil(5 / 2) = 3 calls, no key in two, in first-seen order.
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).containsExactly("k1", "k2");
        assertThat(chunks.get(1)).containsExactly("k3", "k4");
        assertThat(chunks.get(2)).containsExactly("k5");
    }

    @Test
    void ac_fch_16_batch_size_unset_looks_every_distinct_key_up_in_one_call() {
        var chunks = new ArrayList<Set<String>>();
        Enricher<Actor> actors = Enricher.<Actor, String, String>byKeys(keys -> {
            chunks.add(keys);
            return Map.of();
        }).key(Actor::payer, Actor::withPayer).reading();

        actors.enrich("Actor", List.of(new Actor("k1", null), new Actor("k2", null), new Actor("k1", null)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).containsExactly("k1", "k2");
    }
}
