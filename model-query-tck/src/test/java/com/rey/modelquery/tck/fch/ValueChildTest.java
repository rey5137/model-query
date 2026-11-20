package com.rey.modelquery.tck.fch;

import static com.rey.modelquery.tck.fch.FetchTestSupport.limited;
import static com.rey.modelquery.tck.fch.FetchTestSupport.withExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValueChildField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.tck.col.ConstantStatus;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.TaggedCardEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Value children: the basic values of an element collection, loaded {@code through} a path from the parent's root as
 * bags, repeats and {@code NULL}s kept, a converted element read back converted, an {@code @OrderColumn} in its
 * order, a holder's values through a to-one association, rounds, the bound, and the plan-build refusals (spec api/15
 * R-FCH-19, D-247; AC-FCH-30 to AC-FCH-32). The seed is {@code TckFixture#seedTaggedCards}.
 */
class ValueChildTest {

    /** Every value child of {@link CardValues}. */
    private static final FetchPlan<CardValues> ALL_VALUES = FetchPlan.of(QCardValues.ALL)
            .values(QCardValues.TAGS).values(QCardValues.TAG_SET).values(QCardValues.STATES)
            .values(QCardValues.SCORES).values(QCardValues.HOLDER_TAGS);

    /** Cards 1 to 5, by id, with {@code plan}. */
    private static ModelQuery<TaggedCardEntity, Long, CardValues> cards(FetchPlan<CardValues> plan) {
        return QCardValues.query().orderBy(QCardValues.ID.asc()).fetch(plan).build();
    }

    @TckTest
    void ac_fch_30_value_children_load_as_bags_with_repeats_nulls_and_converted_elements(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "fch-30-value-children", ds -> withExecutor(ds, TaggedCardEntity.class,
                executor -> {
                    List<CardValues> loaded = executor.list(cards(ALL_VALUES), Limit.unlimited());
                    assertThat(loaded).extracting(CardValues::id).containsExactly(1L, 2L, 3L, 4L, 5L);
                    CardValues alpha = loaded.get(0);
                    CardValues beta = loaded.get(1);
                    // A bag: the repeated tag twice and the NULL tag kept; NULL sorts where the vendor puts it.
                    assertThat(alpha.tags()).containsExactlyInAnyOrder("red", "blue", "red", null);
                    assertThat(alpha.tags().stream().filter(Objects::nonNull))
                            .as("ordered by the value after the key").containsExactly("blue", "red", "red");
                    assertThat(alpha.tagSet()).containsExactlyInAnyOrder("red", "blue", null);
                    assertThat(beta.tags()).containsExactly("green");
                    assertThat(beta.tagSet()).containsExactly("green");
                    // Converted from one stored letter each, ordered by the stored letter: C, P, P.
                    assertThat(alpha.states()).containsExactly(ConstantStatus.CLOSED, ConstantStatus.PENDING,
                            ConstantStatus.PENDING);
                    assertThat(beta.states()).containsExactlyInAnyOrder(ConstantStatus.FAILED, null);
                    // The @OrderColumn order, seeded backwards, its NULL at its index.
                    assertThat(alpha.scores()).containsExactly(30, null, 10, 30);
                    assertThat(loaded.get(3).scores()).containsExactly(5);
                    // A card with no element keeps the empty value it was mapped with.
                    assertThat(loaded.get(4)).isEqualTo(new CardValues(5L, "epsilon", List.of(),
                            Set.of(), List.of(), List.of(), List.of()));
                    assertThat(beta.scores()).isEmpty();
                }));
    }

    @TckTest
    void ac_fch_31_a_holders_values_load_through_a_to_one_association(TckDatabase db) {
        var q = cards(FetchPlan.of(QCardValues.ALL).values(QCardValues.HOLDER_TAGS));

        withExecutor(JoinTestSupport.dataSource(db), TaggedCardEntity.class, executor -> {
            List<CardValues> loaded = executor.list(q, Limit.unlimited());
            // Cards 3 and 4 are held by cards 1 and 2; the others have no holder, so the INNER join finds nothing.
            assertThat(loaded.get(2).holderTags()).containsExactlyInAnyOrder("red", "blue", "red", null);
            assertThat(loaded.get(3).holderTags()).containsExactly("green");
            assertThat(loaded).filteredOn(card -> card.id() != 3L && card.id() != 4L)
                    .allSatisfy(card -> assertThat(card.holderTags()).isEmpty());
            assertThat(loaded).allSatisfy(card -> assertThat(card.tags()).as("not in the plan").isEmpty());
        });
    }

    @TckTest
    void ac_fch_31_keys_over_the_vendors_limit_load_in_rounds_with_the_same_values(TckDatabase db) {
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(limited(db, 2)));
        var q = cards(FetchPlan.of(QCardValues.ALL).values(QCardValues.TAGS));
        List<List<String>> unlimited = new ArrayList<>();
        withExecutor(JoinTestSupport.dataSource(db), TaggedCardEntity.class, executor ->
                executor.list(q, Limit.unlimited()).forEach(card -> unlimited.add(sorted(card.tags()))));

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, TaggedCardEntity.class, config,
                executor -> assertThat(executor.list(q, Limit.unlimited())).extracting(card -> sorted(card.tags()))
                        .containsExactlyElementsOf(unlimited)));
        assertThat(sql).as("the page, then five keys in rounds of 2, 2 and 1").hasSize(4);
        assertThat(sql.subList(1, 4)).extracting(FetchTestSupport::binds).containsExactly(2L, 2L, 1L);
        assertThat(unlimited.get(0)).hasSize(4);
    }

    @TckTest
    void ac_fch_32_max_per_parent_counts_rows_before_a_set_collapses_them_with_mq2603(TckDatabase db) {
        // Card 1 has four tag rows, three distinct: a bound of 4 holds, a bound of 3 is over for the list and the set.
        withExecutor(JoinTestSupport.dataSource(db), TaggedCardEntity.class, executor -> {
            var held = cards(FetchPlan.of(QCardValues.ALL).values(QCardValues.TAG_SET, v -> v.maxPerParent(4)));
            assertThat(executor.list(held, Limit.unlimited()).get(0).tagSet()).hasSize(3);
            for (ValueChildField<CardValues, String> field : List.of(QCardValues.TAGS, QCardValues.TAG_SET)) {
                var over = cards(FetchPlan.of(QCardValues.ALL).values(field, v -> v.maxPerParent(3)));
                assertThatThrownBy(() -> executor.list(over, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2603))
                        .hasMessage("MQ2603: CardValues." + field.name() + ": key 1 has 4 values, more than "
                                + "maxPerParent(3); raise the bound");
            }
        });
    }

    @TckTest
    void ac_fch_32_a_value_statement_joining_another_collection_is_mq1713_before_any_statement(TckDatabase db) {
        // A hand-written field the processor refuses (MQ3406): the cards a card holds, then their tags, which would
        // repeat a tag per held card. The page finds no card, and nothing runs.
        var q = QCardValues.query().where(f -> f.eq(QCardValues.ID, -1L))
                .fetch(FetchPlan.of(QCardValues.ALL).values(heldTags())).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, TaggedCardEntity.class, executor ->
                assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1713))
                        .hasMessage("MQ1713: CardValues.heldTags: the value child's statement joins the collection "
                                + "'held' besides the element collection 'tags', which would repeat its values; "
                                + "reach the element collection through to-one associations only")));
        assertThat(sql).as("refused at the plan build, before the parent's statement").isEmpty();
    }

    @TckTest
    void ac_fch_32_a_value_round_over_the_bind_limit_is_mq1307_before_any_statement(TckDatabase db) {
        // A bind limit of 1: the bound's limit binds one, so a one-key round binds two.
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(limited(db, 1024, 1)));
        var q = QCardValues.query().where(f -> f.eq(QCardValues.ID, -1L))
                .fetch(FetchPlan.of(QCardValues.ALL).values(QCardValues.TAGS, v -> v.maxPerParent(2))).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, TaggedCardEntity.class, config,
                executor -> assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1307))
                        .hasMessage("MQ1307: CardValues.tags: a statement binds 2 values, more than the 1 bind "
                                + "parameters one statement takes; narrow its filters, since a query's own statement "
                                + "is never split across statements")));
        assertThat(sql).as("refused before any statement").isEmpty();
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted(Comparator.nullsFirst(Comparator.naturalOrder())).toList();
    }

    /** The tags of the cards a card holds: a path across the collection {@code held}, which only MQ1713 stops. */
    private static ValueChildField<CardValues, String> heldTags() {
        TableField<?, ?> held = TableField.<TaggedCardEntity, TaggedCardEntity>join(QCardValues.ROOT, "held",
                JoinType.INNER);
        return new ValueChildField<>() {
            @Override
            public String name() {
                return "heldTags";
            }

            @Override
            public ColumnField<CardValues, ?, ?> key() {
                return QCardValues.ID;
            }

            @Override
            public TableField<?, ?> owner() {
                return held;
            }

            @Override
            public String attribute() {
                return "tags";
            }

            @Override
            public boolean isOrdered() {
                return false;
            }

            @Override
            public boolean ordersByValue() {
                return true;
            }

            @Override
            public CardValues with(CardValues parent, List<String> values) {
                return parent;
            }
        };
    }
}
