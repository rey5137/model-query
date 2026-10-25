package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.core.EntityJoinRenderer;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.ExpectedNarrowing;
import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.TreatSite;
import com.rey.modelquery.core.TreatRenderer;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code one}, {@code first} and {@code one(q, key)} over in-memory H2 (engine/20 R-EXE-12). H2 sorts NULLs first in
 * an ascending order, so a {@code first} without the explicit {@code nullsLast} would return the NULL row here.
 */
class SingleRowReadTest {

    record Row(Long id, Integer a) {}

    record Keyed(String id) {}

    record Group(Integer c, Long n) {}

    record Total(Long n) {}

    record Pair(Integer first, Integer second) {}

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Row, KeysetRowEntity, Long> ID =
            ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> A =
            ColumnField.of(Row.class, ROOT, "a", Integer.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> C =
            ColumnField.of(Row.class, ROOT, "c", Integer.class);

    /** The id as {@code "k<id>"}, so a key passed to {@code one(q, key)} is converted as {@code whereKey}'s is. */
    private static final ColumnField<Keyed, KeysetRowEntity, String> KEYED_ID = ColumnField.of(Keyed.class, ROOT,
            "id", String.class, Long.class, new ColumnConverter<String, Long>() {
                @Override
                public String toModel(Long attribute) {
                    return "k" + attribute;
                }

                @Override
                public Long toAttribute(String model) {
                    return Long.valueOf(model.substring(1));
                }
            });

    private static final ColumnField<Group, KeysetRowEntity, Integer> GROUP_C =
            ColumnField.of(Group.class, ROOT, "c", Integer.class);
    private static final AggregateField<Group, Long> GROUP_N = Agg.count(ROOT);
    private static final AggregateField<Total, Long> TOTAL_N = Agg.count(ROOT);

    private static final TableField<BindLimitPairEntity, BindLimitPairEntity> PAIRS =
            TableField.root(BindLimitPairEntity.class);
    private static final ColumnField<Pair, BindLimitPairEntity, Integer> FIRST =
            ColumnField.of(Pair.class, PAIRS, "firstNo", Integer.class);
    private static final ColumnField<Pair, BindLimitPairEntity, Integer> SECOND =
            ColumnField.of(Pair.class, PAIRS, "secondNo", Integer.class);

    /** Every row, keyed by id, in no order. */
    private static final ModelQuery.Builder<KeysetRowEntity, Long, Row> ROWS = rows(new AtomicInteger())
            .primaryKey(PrimaryKey.of(ID));

    /** Every row, keyed by the nullable, non-unique, non-id column {@code a}. */
    private static final ModelQuery.Builder<KeysetRowEntity, Integer, Row> BY_A = rows(new AtomicInteger())
            .primaryKey(PrimaryKey.of(A));

    private static final ModelQuery.Builder<KeysetRowEntity, Object, Group> GROUPS = ModelQuery
            .builder(ROOT, row -> new Group(row.get(GROUP_C), row.get(GROUP_N)))
            .select(SelectSet.of(GROUP_C, GROUP_N))
            .groupBy(GROUP_C);

    private static SessionFactory sessions;

    /**
     * Four rows: {@code a} is NULL on the lowest id, {@code c} pairs them up.
     *
     * <pre>
     * id  a     c
     * 1   NULL  0
     * 2   2     0
     * 3   1     1
     * 4   3     1
     * </pre>
     */
    @BeforeAll
    static void seed() {
        sessions = new Configuration()
                .addAnnotatedClass(KeysetRowEntity.class)
                .addAnnotatedClass(BindLimitPairEntity.class)
                .addAnnotatedClass(LockParentEntity.class)
                .addAnnotatedClass(LockChildEntity.class)
                .addAnnotatedClass(HolderEntity.class)
                .addAnnotatedClass(AnimalEntity.class)
                .addAnnotatedClass(DogEntity.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:singlerow;DB_CLOSE_DELAY=-1")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .build());
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new KeysetRowEntity(1, null, 10, 0));
            em.persist(new KeysetRowEntity(2, 2, 20, 0));
            em.persist(new KeysetRowEntity(3, 1, 30, 1));
            em.persist(new KeysetRowEntity(4, 3, null, 1));
            em.persist(new BindLimitPairEntity(1, 1, 1));
            em.persist(new BindLimitPairEntity(1, 2, 2));
            em.persist(new BindLimitPairEntity(2, 1, 3));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    // ---- AC-EXE-11

    @Test
    void ac_exe_11_one_returns_the_single_row_or_empty() {
        withExecutor(executor -> {
            assertThat(executor.one(ROWS.where(f -> f.eq(ID, 3L)).build())).contains(new Row(3L, 1));
            assertThat(executor.one(ROWS.where(f -> f.eq(ID, 99L)).build())).isEmpty();
        });
    }

    @Test
    void ac_exe_11_a_second_row_throws_mq2003_before_afterMap_or_an_enricher_sees_it() {
        AtomicInteger mapped = new AtomicInteger();
        AtomicInteger afterMapped = new AtomicInteger();
        List<Integer> enriched = new ArrayList<>();
        ModelQuery.Builder<KeysetRowEntity, Long, Row> enrichedRows = rows(mapped)
                .primaryKey(PrimaryKey.of(ID))
                .afterMap((model, row) -> afterMapped.incrementAndGet())
                .fetch(FetchPlan.of(SelectSet.of(ID, A)).enrich(Enricher.of(page -> {
                    enriched.add(page.size());
                    return page;
                })));
        withExecutor(executor -> {
            assertMq(() -> executor.one(enrichedRows.where(f -> f.eq(C, 0)).build()), MqCode.MQ2003,
                    "Row: one(query) found more than one row");
            assertThat(mapped).hasValue(0);
            assertThat(afterMapped).hasValue(0);
            assertThat(enriched).isEmpty();

            assertThat(executor.one(enrichedRows.where(f -> f.eq(ID, 2L)).build())).contains(new Row(2L, 2));
            assertThat(mapped).hasValue(1);
            assertThat(afterMapped).hasValue(1);
            assertThat(enriched).containsExactly(1);
        });
    }

    // ---- AC-EXE-12

    @Test
    void ac_exe_12_first_closes_the_order_with_a_nullable_key_sorted_nulls_last() {
        withExecutor(executor -> {
            // Row 1's NULL key would sort first on H2 without the explicit NULLS LAST.
            assertThat(executor.first(BY_A.build())).contains(new Row(3L, 1));
            assertThat(executor.first(ROWS.build())).contains(new Row(1L, null));
        });
    }

    @Test
    void ac_exe_12_first_sorts_nulls_last_only_for_a_key_column_that_can_be_null() {
        try (EntityManager em = sessions.createEntityManager()) {
            var built = ROWS.build().buildQuery(em.getCriteriaBuilder(), Phase.MODEL, RenderOptions.portable());
            // The @Id and a nullable=false column take a plain ascending order; a nullable one needs nullsLast.
            assertThat(DefaultModelQueryExecutor.mayBeNull(ID, built.joins())).isFalse();
            assertThat(DefaultModelQueryExecutor.mayBeNull(C, built.joins())).isFalse();
            assertThat(DefaultModelQueryExecutor.mayBeNull(A, built.joins())).isTrue();
        }
    }

    @Test
    void ac_exe_12_a_joined_entity_column_is_judged_by_the_statement_record_not_the_provider_node_type() {
        var inner = EntityJoin.of("inner", KeysetRowEntity.class, JoinKind.INNER, List.of(EntityJoin.on(ID, "id")));
        var left = EntityJoin.of("left", KeysetRowEntity.class, JoinKind.LEFT, List.of(EntityJoin.on(ID, "id")));
        var innerC = ColumnField.of(Row.class, inner.root(), "c", Integer.class);
        var leftC = ColumnField.of(Row.class, left.root(), "c", Integer.class);
        try (EntityManager em = sessions.createEntityManager()) {
            var cb = em.getCriteriaBuilder();
            // A provider whose entity join is a plain From, so no Join type says LEFT.
            RenderOptions options = RenderOptions.portable().withEntityJoinRenderer(new EntityJoinRenderer() {
                @Override
                public <T> From<?, T> join(From<?, ?> anchor, Class<T> entity, JoinKind kind,
                        Function<From<?, T>, Predicate> on) {
                    Root<T> node = cb.createQuery().from(entity);
                    on.apply(node);
                    return node;
                }
            });
            var built = rows(new AtomicInteger()).joinEntity(inner).joinEntity(left).build()
                    .buildQuery(cb, Phase.MODEL, options);
            // c is a nullable=false column: only the LEFT record makes it NULL-able.
            assertThat(DefaultModelQueryExecutor.mayBeNull(innerC, built.joins())).isFalse();
            assertThat(DefaultModelQueryExecutor.mayBeNull(leftC, built.joins())).isTrue();
        }
    }

    @Test
    void ac_exe_12_a_left_provider_join_is_outer_even_when_the_statement_record_says_inner() {
        var parent = TableField.root(LockParentEntity.class);
        var parentId = ColumnField.of(Long.class, parent, "id", Long.class);
        var child = EntityJoin.of("child", LockChildEntity.class, JoinKind.INNER, List.of(EntityJoin.on(parentId, "id")));
        var childId = ColumnField.of(Long.class, child.root(), "id", Long.class);
        try (EntityManager em = sessions.createEntityManager()) {
            var cb = em.getCriteriaBuilder();
            // The provider joins the association as LEFT while the record says INNER: either one is enough.
            RenderOptions options = RenderOptions.portable().withEntityJoinRenderer(new EntityJoinRenderer() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> From<?, T> join(From<?, ?> anchor, Class<T> entity, JoinKind kind,
                        Function<From<?, T>, Predicate> on) {
                    Join<?, T> join = (Join<?, T>) anchor.join("child", JoinType.LEFT);
                    return join.on(on.apply(join));
                }
            });
            var built = ModelQuery.builder(parent, row -> row.get(parentId)).select(SelectSet.of(parentId))
                    .joinEntity(child).build().buildQuery(cb, Phase.MODEL, options);
            assertThat(DefaultModelQueryExecutor.mayBeNull(parentId, built.joins())).isFalse();
            assertThat(DefaultModelQueryExecutor.mayBeNull(childId, built.joins())).isTrue();
        }
    }

    @Test
    void ac_exe_12_a_column_under_an_optional_embedded_component_may_be_null() {
        var holder = TableField.root(HolderEntity.class);
        var own = ColumnField.of(String.class, holder, "own", String.class);
        var inner = ColumnField.of(String.class, holder, "part.label", String.class);
        try (EntityManager em = sessions.createEntityManager()) {
            var built = ModelQuery.builder(holder, row -> row.get(own)).select(SelectSet.of(own, inner)).build()
                    .buildQuery(em.getCriteriaBuilder(), Phase.MODEL, RenderOptions.portable());
            // label is nullable=false, but a NULL component reads it as NULL.
            assertThat(DefaultModelQueryExecutor.mayBeNull(own, built.joins())).isFalse();
            assertThat(DefaultModelQueryExecutor.mayBeNull(inner, built.joins())).isTrue();
        }
    }

    @Test
    void ac_exe_12_a_column_read_through_a_treat_may_be_null() {
        var animal = TableField.root(AnimalEntity.class);
        var name = ColumnField.of(String.class, animal, "name", String.class);
        var tag = ColumnField.of(String.class, animal.treat(DogEntity.class), "tag", String.class);
        RenderOptions options = RenderOptions.portable().withTreatRenderer(new TreatRenderer() {
            @Override
            @SuppressWarnings("unchecked")
            public <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype) {
                return cb.treat((Root<T>) from, subtype);
            }

            @Override
            public Optional<String> refusal(TreatSite site) {
                return Optional.empty();
            }

            @Override
            public Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement,
                    StatementShape shape, List<ExpectedNarrowing> expected) {
                return Optional.empty();
            }
        });
        try (EntityManager em = sessions.createEntityManager()) {
            var built = ModelQuery.builder(animal, row -> row.get(name)).select(SelectSet.of(name, tag)).build()
                    .buildQuery(em.getCriteriaBuilder(), Phase.MODEL, options);
            assertThat(DefaultModelQueryExecutor.mayBeNull(name, built.joins())).isFalse();
            assertThat(DefaultModelQueryExecutor.mayBeNull(tag, built.joins())).isTrue();
        }
    }

    @Entity
    @Table(name = "sr_holders")
    public static class HolderEntity {
        @Id
        Long id;

        @Column(name = "own", nullable = false)
        String own;

        @Embedded
        Part part;
    }

    @Embeddable
    public static class Part {
        @Column(name = "label", nullable = false)
        String label;
    }

    @Entity
    @Table(name = "sr_animals")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorColumn(name = "kind")
    public static class AnimalEntity {
        @Id
        Long id;

        @Column(name = "name", nullable = false)
        String name;
    }

    @Entity
    public static class DogEntity extends AnimalEntity {
        @Column(name = "tag", nullable = false)
        String tag;
    }

    @Test
    void ac_exe_12_first_orders_by_order_by_then_the_key_where_order_by_does_not_cover_it() {
        withExecutor(executor -> {
            assertThat(executor.first(ROWS.orderBy(C.desc()).build())).contains(new Row(3L, 1));
            assertThat(executor.first(ROWS.orderBy(C.asc()).build())).contains(new Row(1L, null));
            assertThat(executor.first(ROWS.orderBy(ID.desc()).build())).contains(new Row(4L, 3));
            assertThat(executor.first(ROWS.where(f -> f.eq(ID, 99L)).build())).isEmpty();
        });
    }

    @Test
    void ac_exe_12_a_grouped_first_closes_the_order_with_its_group_keys() {
        withExecutor(executor -> {
            assertThat(executor.first(GROUPS.build())).contains(new Group(0, 2L));
            assertThat(executor.first(GROUPS.orderBy(GROUP_C.desc()).build())).contains(new Group(1, 2L));
            // Both groups count 2, so the group key breaks the tie.
            assertThat(executor.first(GROUPS.orderBy(GROUP_N.desc()).build())).contains(new Group(0, 2L));
        });
    }

    @Test
    void ac_exe_12_first_without_an_order_by_or_a_key_throws_mq2203_unless_it_only_aggregates() {
        var keyless = rows(new AtomicInteger());
        var total = ModelQuery.builder(ROOT, row -> new Total(row.get(TOTAL_N))).select(SelectSet.of(TOTAL_N));
        withExecutor(executor -> {
            assertMq(() -> executor.first(keyless.build()), MqCode.MQ2203,
                    "Row: first(query) needs an orderBy or a primary key to choose the same row on every database");
            // An aggregate-only query has one row, so it needs neither.
            assertThat(executor.first(total.build())).contains(new Total(4L));
            assertThat(executor.first(keyless.orderBy(A.asc().nullsLast()).build())).contains(new Row(3L, 1));
            assertThat(executor.first(total.orderBy(TOTAL_N.asc()).build())).contains(new Total(4L));
        });
    }

    // ---- AC-EXE-13

    @Test
    void ac_exe_13_one_by_key_ands_the_key_with_the_query_filter() {
        withExecutor(executor -> {
            assertThat(executor.one(ROWS.build(), 3L)).contains(new Row(3L, 1));
            assertThat(executor.one(ROWS.build(), 99L)).isEmpty();
            assertThat(executor.one(ROWS.where(f -> f.eq(A, 1)).build(), 3L)).contains(new Row(3L, 1));
            assertThat(executor.one(ROWS.where(f -> f.eq(A, 2)).build(), 3L)).isEmpty();
            assertThat(executor.one(ROWS.where(f -> f.eq(ID, 2L)).build(), 3L)).isEmpty();
        });
    }

    @Test
    void ac_exe_13_one_by_key_converts_the_key_as_where_key_does() {
        var keyed = ModelQuery.builder(ROOT, row -> new Keyed(row.get(KEYED_ID)))
                .select(SelectSet.of(KEYED_ID))
                .primaryKey(PrimaryKey.of(KEYED_ID))
                .build();
        withExecutor(executor -> assertThat(executor.one(keyed, "k2")).contains(new Keyed("k2")));
    }

    @Test
    void ac_exe_13_one_by_a_composite_key_checks_its_components() {
        var pairs = ModelQuery.builder(PAIRS, row -> new Pair(row.get(FIRST), row.get(SECOND)))
                .select(SelectSet.of(FIRST, SECOND))
                .primaryKey(PrimaryKey.composite(FIRST, SECOND))
                .build();
        withPairExecutor(executor -> {
            assertThat(executor.one(pairs, List.of(1, 2))).contains(new Pair(1, 2));
            assertThat(executor.one(pairs, List.of(2, 2))).isEmpty();
            assertThatThrownBy(() -> executor.one(pairs, List.of(1)))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("a key of 2 components is a list of 2 values, not [1]");
            assertThatThrownBy(() -> executor.one(pairs, Arrays.asList(1, null)))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("a key component is null: [1, null]");
            assertThatThrownBy(() -> executor.one(pairs, null))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Pair: one(query, key) needs a key, and it is null");
        });
    }

    @Test
    void ac_exe_13_a_null_key_throws_illegal_argument() {
        withExecutor(executor -> assertThatThrownBy(() -> executor.one(ROWS.build(), null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Row: one(query, key) needs a key, and it is null"));
    }

    @Test
    void ac_exe_13_a_keyless_or_grouped_query_throws_mq2203() {
        var grouped = GROUPS.primaryKey(PrimaryKey.of(GROUP_C)).build();
        withExecutor(executor -> {
            assertMq(() -> executor.one(rows(new AtomicInteger()).build(), 1L), MqCode.MQ2203,
                    "Row: one(query, key) needs a primary key to filter on, and primaryKey(...) was not set");
            assertMq(() -> executor.one(grouped, 0), MqCode.MQ2203,
                    "Group: one(query, key) needs a primary key to filter on, and a grouped query has none");
        });
    }

    @Test
    void ac_pag_31_a_non_id_key_matching_two_rows_throws_mq2213() {
        var byC = rows(new AtomicInteger()).primaryKey(PrimaryKey.of(C)).build();
        withExecutor(executor -> assertMq(() -> executor.one(byC, 0), MqCode.MQ2213,
                "Row: more than one row has primary key 0"));
    }

    /** Every row's id and {@code a}, counting the rows the mapper maps. */
    private static ModelQuery.Builder<KeysetRowEntity, Object, Row> rows(AtomicInteger mapped) {
        return ModelQuery.builder(ROOT, row -> {
            mapped.incrementAndGet();
            return new Row(row.get(ID), row.get(A));
        }).select(SelectSet.of(ID, A));
    }

    private static void assertMq(ThrowingCallable call, MqCode code, String message) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ModelQueryExecutionException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessage(code.code() + ": " + message);
    }

    private static void withExecutor(Consumer<ModelQueryExecutor<KeysetRowEntity>> work) {
        try (EntityManager em = sessions.createEntityManager()) {
            work.accept(ModelQueryExecutor.create(em, KeysetRowEntity.class, ModelQueryConfig.defaults()));
        }
    }

    private static void withPairExecutor(Consumer<ModelQueryExecutor<BindLimitPairEntity>> work) {
        try (EntityManager em = sessions.createEntityManager()) {
            work.accept(ModelQueryExecutor.create(em, BindLimitPairEntity.class, ModelQueryConfig.defaults()));
        }
    }
}
