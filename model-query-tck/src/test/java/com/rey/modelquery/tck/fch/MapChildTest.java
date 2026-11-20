package com.rey.modelquery.tck.fch;

import static com.rey.modelquery.tck.fch.FetchTestSupport.limited;
import static com.rey.modelquery.tck.fch.FetchTestSupport.withExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.MapChildField;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.tck.col.ConstantStatus;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderStatus;
import com.rey.modelquery.tck.col.TaggedCardEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Map children: basic, enum-keyed and embeddable element model element collection maps (spec api/15 R-FCH-20, D-248;
 * AC-FCH-39 to AC-FCH-44). The seed is {@code TckFixture#seedTaggedCards}.
 */
class MapChildTest {

    private static final FetchPlan<CardMaps> ALL_MAPS = FetchPlan.of(QCardMaps.ALL)
            .map(QCardMaps.LABELS).map(QCardMaps.COUNTS).map(QCardMaps.SPOTS).map(QCardMaps.HOLDER_LABELS);

    private static ModelQuery<TaggedCardEntity, Long, CardMaps> cards(FetchPlan<CardMaps> plan) {
        return QCardMaps.query().orderBy(QCardMaps.ID.asc()).fetch(plan).build();
    }

    @TckTest
    void ac_fch_39_map_children_load_entries_in_unspecified_order(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "fch-32-map-children", ds -> withExecutor(ds, TaggedCardEntity.class,
                executor -> {
                    List<CardMaps> loaded = executor.list(cards(ALL_MAPS), Limit.unlimited());
                    assertThat(loaded).extracting(CardMaps::id).containsExactly(1L, 2L, 3L, 4L, 5L);
                    CardMaps alpha = loaded.get(0);
                    CardMaps beta = loaded.get(1);

                    // Basic string map
                    assertThat(alpha.labels()).containsExactlyInAnyOrderEntriesOf(
                            Map.of("env", "prod", "tier", "gold"));
                    assertThat(beta.labels()).containsExactlyInAnyOrderEntriesOf(
                            Map.of("role", "admin"));

                    // Enum key map
                    assertThat(alpha.counts()).containsExactlyInAnyOrderEntriesOf(
                            Map.of(OrderStatus.PAID, 10, OrderStatus.NEW, 5));
                    assertThat(beta.counts()).containsExactlyInAnyOrderEntriesOf(
                            Map.of(OrderStatus.SHIPPED, 3));

                    // Embeddable element model map
                    assertThat(alpha.spots()).hasSize(2);
                    assertThat(alpha.spots().get("primary")).isEqualTo(
                            new SpotRow("Rome", new BigDecimal("1.25"), ConstantStatus.PENDING));
                    assertThat(alpha.spots().get("secondary")).isEqualTo(
                            new SpotRow("Paris", new BigDecimal("2.50"), ConstantStatus.CLOSED));
                    assertThat(beta.spots()).isEmpty();

                    // Empty parent keeps empty map
                    assertThat(loaded.get(4)).isEqualTo(new CardMaps(5L, "epsilon",
                            Map.of(), Map.of(), Map.of(), Map.of()));
                }));
    }

    @TckTest
    void ac_fch_40_a_holders_map_loads_through_a_to_one_association(TckDatabase db) {
        var q = cards(FetchPlan.of(QCardMaps.ALL).map(QCardMaps.HOLDER_LABELS));

        withExecutor(JoinTestSupport.dataSource(db), TaggedCardEntity.class, executor -> {
            List<CardMaps> loaded = executor.list(q, Limit.unlimited());
            // Cards 3 and 4 held by 1 and 2; card 3 sees card 1's labels, card 4 sees card 2's labels.
            assertThat(loaded.get(2).holderLabels()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("env", "prod", "tier", "gold"));
            assertThat(loaded.get(3).holderLabels()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("role", "admin"));
            assertThat(loaded).filteredOn(card -> card.id() != 3L && card.id() != 4L)
                    .allSatisfy(card -> assertThat(card.holderLabels()).isEmpty());
        });
    }

    @TckTest
    void ac_fch_40_keys_over_the_vendors_limit_load_in_rounds_with_the_same_maps(TckDatabase db) {
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(limited(db, 2)));
        var q = cards(FetchPlan.of(QCardMaps.ALL).map(QCardMaps.LABELS));
        List<Map<String, String>> unlimited = new ArrayList<>();
        withExecutor(JoinTestSupport.dataSource(db), TaggedCardEntity.class, executor ->
                executor.list(q, Limit.unlimited()).forEach(card -> unlimited.add(card.labels())));

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, TaggedCardEntity.class, config,
                executor -> assertThat(executor.list(q, Limit.unlimited())).extracting(CardMaps::labels)
                        .containsExactlyElementsOf(unlimited)));
        assertThat(sql).as("the page, then five keys in rounds of 2, 2 and 1").hasSize(4);
        assertThat(sql.subList(1, 4)).extracting(FetchTestSupport::binds).containsExactly(2L, 2L, 1L);
    }

    @TckTest
    void ac_fch_41_duplicate_map_key_for_one_parent_throws_mq2609(TckDatabase db) {
        var plan = FetchPlan.of(QCardCaseLabels.ALL).map(QCardCaseLabels.CASE_LABELS);
        var q = QCardCaseLabels.query().orderBy(QCardCaseLabels.ID.asc()).fetch(plan).build();

        withExecutor(JoinTestSupport.dataSource(db), TaggedCardEntity.class, executor ->
                assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2609))
                        .hasMessage("MQ2609: CardCaseLabels.caseLabels: parent key 1 has duplicate entries for map "
                                + "key foo; two entries share the same Java key"));
    }

    @TckTest
    void ac_fch_42_max_per_parent_bounds_entries_and_throws_mq2603(TckDatabase db) {
        // Card 1 has two label rows: a bound of 2 holds, a bound of 1 throws MQ2603.
        withExecutor(JoinTestSupport.dataSource(db), TaggedCardEntity.class, executor -> {
            var held = cards(FetchPlan.of(QCardMaps.ALL).map(QCardMaps.LABELS, v -> v.maxPerParent(2)));
            assertThat(executor.list(held, Limit.unlimited()).get(0).labels()).hasSize(2);

            var over = cards(FetchPlan.of(QCardMaps.ALL).map(QCardMaps.LABELS, v -> v.maxPerParent(1)));
            assertThatThrownBy(() -> executor.list(over, Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2603))
                        .hasMessage("MQ2603: CardMaps.labels: key 1 has 2 entries, more than "
                                + "maxPerParent(1); raise the bound");
        });
    }

    @TckTest
    void ac_fch_43_a_map_statement_joining_another_collection_is_mq1713_before_any_statement(TckDatabase db) {
        var q = QCardMaps.query().where(f -> f.eq(QCardMaps.ID, -1L))
                .fetch(FetchPlan.of(QCardMaps.ALL).map(heldLabels())).build();

        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, TaggedCardEntity.class, executor ->
                assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1713))
                        .hasMessage("MQ1713: CardMaps.heldLabels: the map child's statement joins the collection "
                                + "'held' besides the element collection 'labels', which would repeat its entries; "
                                + "reach the element collection through to-one associations only")));
        assertThat(sql).as("refused at the plan build, before the parent's statement").isEmpty();
    }

    @TckTest
    void ac_fch_44_list_child_through_map_association_resolves(TckDatabase db) {
        var plan = FetchPlan.of(QCardHeldList.ALL).child(QCardHeldList.HELD, FetchPlan.of(QHeldCard.ALL));
        var q = QCardHeldList.query().orderBy(QCardHeldList.ID.asc()).fetch(plan).build();

        withExecutor(JoinTestSupport.dataSource(db), TaggedCardEntity.class, executor -> {
            List<CardHeldList> loaded = executor.list(q, Limit.unlimited());
            CardHeldList alpha = loaded.get(0);
            assertThat(alpha.held()).singleElement().satisfies(held -> {
                assertThat(held.id()).isEqualTo(3L);
                assertThat(held.name()).isEqualTo("gamma");
            });
            assertThat(loaded.get(1).held()).isEmpty();
        });
    }

    @TckTest
    void ac_fch_46_element_model_map_values_read_all_properties(TckDatabase db) {
        var plan = FetchPlan.of(QCardMaps.ALL).map(QCardMaps.SPOTS);
        var q = cards(plan);
        withExecutor(JoinTestSupport.dataSource(db), TaggedCardEntity.class, executor -> {
            List<CardMaps> loaded = executor.list(q, Limit.unlimited());
            CardMaps alpha = loaded.get(0);
            assertThat(alpha.spots()).hasSize(2);
            SpotRow primary = alpha.spots().get("primary");
            assertThat(primary.city()).isEqualTo("Rome");
            assertThat(primary.lat()).isEqualByComparingTo("1.25");
            assertThat(primary.status()).isEqualTo(ConstantStatus.PENDING);
        });
    }

    private static MapChildField<CardMaps, String, String> heldLabels() {
        TableField<?, ?> held = TableField.<TaggedCardEntity, TaggedCardEntity>join(QCardMaps.ROOT, "held",
                JoinType.INNER);
        return new MapChildField<>() {
            @Override
            public String name() {
                return "heldLabels";
            }

            @Override
            public ColumnField<CardMaps, ?, ?> key() {
                return QCardMaps.ID;
            }

            @Override
            public TableField<?, ?> owner() {
                return held;
            }

            @Override
            public String attribute() {
                return "labels";
            }

            @Override
            public CardMaps with(CardMaps parent, Map<String, String> entries) {
                return parent;
            }
        };
    }
}
