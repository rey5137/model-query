package com.rey.modelquery.tck.exe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.ConstantStatus;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.SpotValue;
import com.rey.modelquery.tck.col.TaggedCardEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * A filter through an element collection (D-249): on a basic element, a converted one, an embeddable element's
 * attribute and a map's value, it renders as R-EXE-16's semi-join and returns each card once however many of its
 * elements match; {@code exists} over the generated table constant, bare or with a group; and a statement that selects
 * the element keeps the join, one row per element (R-EXE-04). The seed is {@code TckFixture#seedTaggedCards}: card 1
 * tags red, blue, red and {@code NULL}, card 2 green; card 1 two spots in Rome and one in Paris (AC-EXE-30).
 */
class ElementFilterTest {

    /** A card and one of its tags: the element selected through a hand-written join. */
    record CardTag(Long id, String tag) {}

    private static final TableField<TaggedCardEntity, TaggedCardEntity> CARDS = TableField.root(TaggedCardEntity.class);
    private static final TableField<TaggedCardEntity, String> TAGS = TableField.join(CARDS, "tags", JoinType.INNER);
    private static final TableField<TaggedCardEntity, SpotValue> SPOTS =
            TableField.join(CARDS, "spots", JoinType.INNER);
    private static final ColumnField<CardTag, TaggedCardEntity, Long> TAG_CARD =
            ColumnField.of(CardTag.class, CARDS, "id", Long.class);
    private static final ColumnField<CardTag, String, String> TAG_VALUE =
            ColumnField.element(CardTag.class, TAGS, String.class);

    private static ModelQuery<TaggedCardEntity, Long, CardFilters> cards(UnaryOperator<Filters<CardFilters>> where) {
        return QCardFilters.query().select(QCardFilters.ALL).orderBy(QCardFilters.ID.asc()).where(where).build();
    }

    @TckTest
    void ac_exe_30_a_filter_through_an_element_collection_returns_each_card_once(TckDatabase db) {
        var red = cards(f -> f.eq(QCardFilters.TAG, "red"));
        var reads = new ArrayList<Object>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-30-element-filters", ds -> withExecutor(ds,
                executor -> {
                    // Card 1 holds red twice: a join would return it twice, and count it twice.
                    reads.add(ids(executor.list(red, Limit.unlimited())));
                    reads.add(executor.count(red));
                    reads.add(ids(executor.list(cards(f -> f.in(QCardFilters.TAG, List.of("red", "green"))),
                            Limit.unlimited())));
                    reads.add(ids(executor.list(cards(f -> f.eq(QCardFilters.STATE, ConstantStatus.PENDING)),
                            Limit.unlimited())));
                    reads.add(ids(executor.list(cards(f -> f.eq(QCardFilters.SPOT_CITY, "Rome")),
                            Limit.unlimited())));
                    reads.add(ids(executor.list(cards(f -> f.eq(QCardFilters.LABEL, "admin")), Limit.unlimited())));
                    reads.add(ids(executor.list(cards(f -> f.eq(QCardFilters.MAPPED_SPOT_CITY, "Paris")),
                            Limit.unlimited())));
                    // A root term beside an element term in one or(...), which moves into the sub-query whole.
                    reads.add(ids(executor.list(cards(f -> f.or(g -> g.eq(QCardFilters.TAG, "green"),
                            g -> g.eq(QCardFilters.NAME, "epsilon"))), Limit.unlimited())));
                }));
        assertThat(reads).containsExactly(List.of(1L), 1L, List.of(1L, 2L), List.of(1L), List.of(1L), List.of(2L),
                List.of(1L), List.of(2L, 5L));
        assertThat(sql).hasSize(8).allSatisfy(ElementFilterTest::assertSemiJoin);
    }

    @TckTest
    void ac_exe_30_exists_over_the_generated_table_constant(TckDatabase db) {
        var reads = new ArrayList<List<Long>>();
        SqlSnapshots.assertMatches(db, "exe-30-element-exists", ds -> withExecutor(ds, executor -> {
            reads.add(ids(executor.list(cards(f -> f.exists(QCardFilters.TAGS_TABLE,
                    t -> t.eq(QCardFilters.TAG, "green"))), Limit.unlimited())));
            reads.add(ids(executor.list(cards(f -> f.exists(QCardFilters.TAGS_TABLE)), Limit.unlimited())));
            reads.add(ids(executor.list(cards(f -> f.notExists(QCardFilters.TAGS_TABLE,
                    t -> t.isNotNull(QCardFilters.TAG))), Limit.unlimited())));
            reads.add(ids(executor.list(cards(f -> f.exists(QCardFilters.SPOTS_TABLE,
                    t -> t.eq(QCardFilters.SPOT_CITY, "Paris"))), Limit.unlimited())));
        }));
        assertThat(reads).containsExactly(List.of(2L), List.of(1L, 2L), List.of(3L, 4L, 5L), List.of(1L));
    }

    @TckTest
    void ac_exe_30_a_selected_element_keeps_the_join_one_row_per_element(TckDatabase db) {
        var tags = ModelQuery.builder(CARDS, row -> new CardTag(row.get(TAG_CARD), row.get(TAG_VALUE)))
                .select(SelectSet.of(TAG_CARD, TAG_VALUE))
                .primaryKey(PrimaryKey.of(TAG_CARD))
                .orderBy(TAG_CARD.asc())
                .where(f -> f.in(TAG_VALUE, List.of("red", "green")))
                .build();
        var reads = new ArrayList<Object>();
        List<String> sql = SqlSnapshots.assertMatches(db, "exe-30-element-selected", ds -> withExecutor(ds,
                executor -> {
                    reads.add(executor.list(tags, Limit.unlimited()));
                    reads.add(executor.count(tags));
                }));
        // Each joined row is a result, so count counts rows and agrees with list (R-EXE-04).
        assertThat(reads).containsExactly(
                List.of(new CardTag(1L, "red"), new CardTag(1L, "red"), new CardTag(2L, "green")), 3L);
        assertThat(sql).allSatisfy(statement ->
                assertThat(statement.toLowerCase(Locale.ROOT)).contains(" join ").doesNotContain("exists"));
    }

    @TckTest
    void ac_exe_30_an_element_column_on_a_join_of_embeddables_is_mq1002(TckDatabase db) {
        ColumnField<CardTag, SpotValue, String> spot = ColumnField.element(CardTag.class, SPOTS, String.class);
        var query = ModelQuery.builder(CARDS, row -> new CardTag(row.get(TAG_CARD), null))
                .select(SelectSet.of(TAG_CARD))
                .where(f -> f.eq(spot, "Rome"))
                .build();
        withExecutor(db, executor -> assertThatThrownBy(() -> executor.list(query, Limit.unlimited()))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("CardTag: ColumnField.element(...) reads the value of a join along a "
                        + "collection of basic values")
                .extracting(error -> ((ModelQueryDefinitionException) error).code()).isEqualTo(MqCode.MQ1002));
    }

    @TckTest
    void ac_exe_30_keyset_paging_and_offset_export_ordered_by_an_element_column_are_mq2204(TckDatabase db) {
        // Ordering by the element selects it too (R-QRY-04): a card then spans one row per element (R-PAG-13).
        var byTag = ModelQuery.builder(CARDS, row -> new CardTag(row.get(TAG_CARD), null))
                .select(SelectSet.of(TAG_CARD))
                .primaryKey(PrimaryKey.of(TAG_CARD))
                .orderBy(TAG_VALUE.asc());
        var exported = byTag.build();
        var paged = byTag.keyset().build();
        withExecutor(db, executor -> {
            assertThatThrownBy(() -> executor.page(paged, KeysetSpec.first(5)))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2204))
                    .hasMessageContaining("through the to-many join TaggedCardEntity.tags");
            assertThatThrownBy(() -> executor.export(exported, ExportOptions.of(100), page -> page, row -> {}))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2204))
                    .hasMessageContaining("through the to-many join TaggedCardEntity.tags");
        });
    }

    private static List<Long> ids(List<CardFilters> cards) {
        return cards.stream().map(CardFilters::id).toList();
    }

    /** One {@code exists} in {@code statement}, and no join before it. */
    private static void assertSemiJoin(String statement) {
        String sql = statement.toLowerCase(Locale.ROOT);
        int exists = sql.indexOf("exists");
        assertThat(exists).as("an exists in %s", statement).isPositive();
        assertThat(sql.substring(0, exists)).as("no outer join in %s", statement).doesNotContain(" join ");
        assertThat(sql.indexOf("exists", exists + 1)).as("one exists in %s", statement).isNegative();
    }

    private static void withExecutor(DataSource ds,
            Consumer<ModelQueryExecutor<TaggedCardEntity>> work) {
        try (SessionFactory sessions = JoinTestSupport.sessionFactory(ds)) {
            sessions.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, TaggedCardEntity.class, ModelQueryConfig.defaults())));
        }
    }

    private static void withExecutor(TckDatabase db, Consumer<ModelQueryExecutor<TaggedCardEntity>> work) {
        try (SessionFactory sessions = JoinTestSupport.sessionFactory(db)) {
            sessions.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, TaggedCardEntity.class, ModelQueryConfig.defaults())));
        }
    }
}
