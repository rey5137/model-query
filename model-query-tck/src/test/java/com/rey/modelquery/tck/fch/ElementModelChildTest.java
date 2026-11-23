package com.rey.modelquery.tck.fch;

import static com.rey.modelquery.tck.fch.FetchTestSupport.withExecutor;
import static com.rey.modelquery.tck.fch.FetchTestSupport.limited;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.vendor.ChildStatements;
import com.rey.modelquery.tck.col.ConstantStatus;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.TaggedCardEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.criteria.JoinType;

class ElementModelChildTest {
    private static final FetchPlan<SpotRow> SPOT = FetchPlan.of(QSpotRow.ALL);
    private static final SpotRow ROME = new SpotRow("Rome", new BigDecimal("1.25"), ConstantStatus.PENDING);
    private static final SpotRow PARIS = new SpotRow("Paris", new BigDecimal("2.50"), ConstantStatus.CLOSED);
    private static final SpotRow EMPTY = new SpotRow(null, null, null);

    @TckTest
    void ac_fch_33_element_rows_are_bags_and_ordered_rows_keep_their_index(TckDatabase db) {
        var plan = FetchPlan.of(QCardSpots.ALL).child(QCardSpots.SPOTS, SPOT)
                .child(QCardSpots.ORDERED_SPOTS, SPOT, c -> c.orderBy(QSpotRow.CITY.desc()))
                .child(QCardSpots.HOLDER_SPOTS, SPOT);
        var q = QCardSpots.query().orderBy(QCardSpots.ID.asc()).fetch(plan).build();
        SqlSnapshots.assertMatches(db, "fch-31-element-model-children", ds ->
                withExecutor(ds, TaggedCardEntity.class, executor -> {
                    List<CardSpots> cards = executor.list(q, Limit.unlimited());
                    assertThat(cards.get(0).spots()).containsExactlyInAnyOrder(ROME, ROME, PARIS, EMPTY);
                    assertThat(cards.get(0).orderedSpots()).containsExactly(ROME, EMPTY, PARIS);
                    assertThat(cards.get(2).holderSpots()).containsExactlyInAnyOrder(ROME, ROME, PARIS, EMPTY);
                    assertThat(cards.get(4).spots()).isEmpty();
                }));
    }

    @TckTest
    void ac_fch_34_child_filters_order_and_selection_apply_to_element_columns(TckDatabase db) {
        var selected = FetchPlan.of(SelectSet.of(QSpotRow.CITY));
        var plan = FetchPlan.of(QCardSpots.ALL).child(QCardSpots.SPOTS, selected,
                c -> c.where(f -> f.isNotNull(QSpotRow.CITY)).orderBy(QSpotRow.CITY.asc()));
        var q = QCardSpots.query().orderBy(QCardSpots.ID.asc()).fetch(plan).build();
        withExecutor(JoinTestSupport.dataSource(db), TaggedCardEntity.class, executor -> {
            var rows = executor.list(q, Limit.unlimited()).get(0).spots();
            assertThat(rows).containsExactly(new SpotRow("Paris", null, null), new SpotRow("Rome", null, null),
                    new SpotRow("Rome", null, null));
        });
    }

    @TckTest
    void ac_fch_35_bound_counts_duplicate_and_all_null_rows(TckDatabase db) {
        var plan = FetchPlan.of(QCardSpots.ALL).child(QCardSpots.SPOTS, SPOT, c -> c.maxPerParent(3));
        var q = QCardSpots.query().fetch(plan).build();
        var atBound = QCardSpots.query().orderBy(QCardSpots.ID.asc())
                .fetch(FetchPlan.of(QCardSpots.ALL).child(QCardSpots.SPOTS, SPOT, c -> c.maxPerParent(4))).build();
        withExecutor(JoinTestSupport.dataSource(db), TaggedCardEntity.class, executor -> {
            assertThat(executor.list(atBound, Limit.unlimited()).get(0).spots()).hasSize(4);
            assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2603));
        });
    }

    @TckTest
    void ac_fch_36_other_collection_join_is_refused_before_parent_statement(TckDatabase db) {
        var held = TableField.<TaggedCardEntity, TaggedCardEntity>join(QCardSpots.ROOT, "held", JoinType.INNER);
        var through = TableField.<TaggedCardEntity, Object>join(held, "spots", JoinType.INNER);
        ChildField<CardSpots, SpotRow> invalid = new ChildField<>() {
            public String name() { return "invalid"; }
            public ColumnField<CardSpots, ?, ?> key() { return QCardSpots.ID; }
            public Optional<ColumnField<SpotRow, ?, ?>> foreignKey() { return Optional.empty(); }
            public Optional<TableField<?, ?>> through() { return Optional.of(through); }
            public boolean isToMany() { return true; }
            public boolean isElement() { return true; }
            public ModelQuery.Builder<?, ?, SpotRow> query() { return QCardSpots.SPOTS.query(); }
            public CardSpots with(CardSpots parent, List<SpotRow> children) { return parent; }
        };
        var q = QCardSpots.query().where(f -> f.eq(QCardSpots.ID, -1L))
                .fetch(FetchPlan.of(QCardSpots.ALL).child(invalid, SPOT)).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, TaggedCardEntity.class, executor ->
                assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1713))));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_fch_36_a_field_whose_flag_disagrees_with_its_join_is_mq1713_on_every_path(TckDatabase db) {
        var held = TableField.<TaggedCardEntity, TaggedCardEntity>join(QCardSpots.ROOT, "held", JoinType.INNER);
        // Over the element collection with isElement() false, once with a collection on the path, once without.
        for (TableField<?, ?> through : List.of(TableField.<TaggedCardEntity, Object>join(held, "spots",
                JoinType.INNER), TableField.<TaggedCardEntity, Object>join(QCardSpots.ROOT, "spots",
                JoinType.INNER))) {
            ChildField<CardSpots, SpotRow> lying = new ChildField<>() {
                public String name() { return "lying"; }
                public ColumnField<CardSpots, ?, ?> key() { return QCardSpots.ID; }
                public Optional<ColumnField<SpotRow, ?, ?>> foreignKey() { return Optional.empty(); }
                public Optional<TableField<?, ?>> through() { return Optional.of(through); }
                public boolean isToMany() { return true; }
                public ModelQuery.Builder<?, ?, SpotRow> query() { return QCardSpots.SPOTS.query(); }
                public CardSpots with(CardSpots parent, List<SpotRow> children) { return parent; }
            };
            var plan = FetchPlan.of(QCardSpots.ALL).child(lying, SPOT);
            var q = QCardSpots.query().where(f -> f.eq(QCardSpots.ID, -1L)).fetch(plan).build();
            List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, TaggedCardEntity.class, executor -> {
                assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1713))
                        .hasMessageContaining("CardSpots.lying: the field says it is not an element child");
                assertThatThrownBy(() -> ChildStatements.run(executor, plan.childLoads().get(0), List.of(1L)))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1713));
            }));
            assertThat(sql).as("refused before the parent's statement and any child statement").isEmpty();
        }
    }

    @TckTest
    void ac_fch_36_a_field_claiming_an_element_collection_over_an_entity_collection_is_mq1713(TckDatabase db) {
        var held = TableField.<TaggedCardEntity, TaggedCardEntity>join(QCardSpots.ROOT, "held", JoinType.INNER);
        for (boolean ordered : new boolean[] {false, true}) {
            ChildField<CardSpots, SpotRow> lying = new ChildField<>() {
                public String name() { return "lyingElement"; }
                public ColumnField<CardSpots, ?, ?> key() { return QCardSpots.ID; }
                public Optional<ColumnField<SpotRow, ?, ?>> foreignKey() { return Optional.empty(); }
                public Optional<TableField<?, ?>> through() { return Optional.of(held); }
                public boolean isToMany() { return true; }
                public boolean isElement() { return true; }
                public boolean isOrderedElement() { return ordered; }
                public ModelQuery.Builder<?, ?, SpotRow> query() { return QCardSpots.SPOTS.query(); }
                public CardSpots with(CardSpots parent, List<SpotRow> children) { return parent; }
            };
            var plan = FetchPlan.of(QCardSpots.ALL).child(lying, SPOT);
            var q = QCardSpots.query().where(f -> f.eq(QCardSpots.ID, -1L)).fetch(plan).build();
            List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, TaggedCardEntity.class, executor ->
                    assertThatThrownBy(() -> executor.list(q, Limit.unlimited()))
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1713))
                            .hasMessageContaining("the field says it is an element child")));
            assertThat(sql).as("ordered=" + ordered).isEmpty();
        }
    }

    @TckTest
    void ac_fch_37_rounds_and_child_statements_use_the_element_load(TckDatabase db) {
        var plan = FetchPlan.of(QCardSpots.ALL).child(QCardSpots.SPOTS, SPOT);
        var q = QCardSpots.query().orderBy(QCardSpots.ID.asc()).fetch(plan).build();
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(limited(db, 2)));
        List<String> page = SqlSnapshots.capture(db, ds ->
                withExecutor(ds, TaggedCardEntity.class, config, executor ->
                        assertThat(executor.list(q, Limit.unlimited()).get(0).spots())
                                .containsExactlyInAnyOrder(ROME, ROME, PARIS, EMPTY)));
        List<String> standalone = SqlSnapshots.capture(db, ds ->
                withExecutor(ds, TaggedCardEntity.class, config, executor ->
                        ChildStatements.run(executor, plan.childLoads().get(0), List.of(1L, 2L))));
        assertThat(page).hasSize(4);
        assertThat(standalone).containsExactly(page.get(1));
    }
}
