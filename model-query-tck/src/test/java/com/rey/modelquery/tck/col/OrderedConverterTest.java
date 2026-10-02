package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.DateTimestampConverter;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.InstantTimestampConverter;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** The built-in ordered converters over a {@code Timestamp} attribute (api/10 R-COL-14, api/13 R-AGG-04, D-84). */
class OrderedConverterTest {

    /** One built-in per model; SharedAttributeTest selects one attribute through both. */
    record AtInstant(Long id, Instant placed, Timestamp raw) {}

    record AtDate(Long id, Date placed, Timestamp raw) {}

    record Span(String status, Instant first, Instant last, Date lastDate, Long distinct) {}

    private static final TableField<StampedOrderEntity, StampedOrderEntity> ROOT =
            TableField.root(StampedOrderEntity.class);

    private static final OrderedColumnField<AtInstant, StampedOrderEntity, Long> ID =
            ColumnField.of(AtInstant.class, ROOT, "id", Long.class);
    private static final OrderedColumnField<AtInstant, StampedOrderEntity, Instant> PLACED = ColumnField.of(
            AtInstant.class, ROOT, "placedAt", Instant.class, Timestamp.class, InstantTimestampConverter.INSTANCE);
    private static final OrderedColumnField<AtDate, StampedOrderEntity, Long> DATE_ID =
            ColumnField.of(AtDate.class, ROOT, "id", Long.class);
    private static final OrderedColumnField<AtDate, StampedOrderEntity, Date> PLACED_DATE = ColumnField.of(
            AtDate.class, ROOT, "placedAt", Date.class, Timestamp.class, DateTimestampConverter.INSTANCE);

    private static final ModelQuery.Builder<StampedOrderEntity, Long, AtInstant> AT_INSTANT = ModelQuery
            .builder(ROOT, row -> new AtInstant(row.get(ID), row.get(PLACED), (Timestamp) row.raw(PLACED)))
            .columns(ColumnSet.of(ID, PLACED))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc());
    private static final ModelQuery.Builder<StampedOrderEntity, Long, AtDate> AT_DATE = ModelQuery
            .builder(ROOT, row -> new AtDate(row.get(DATE_ID), row.get(PLACED_DATE), (Timestamp) row.raw(PLACED_DATE)))
            .columns(ColumnSet.of(DATE_ID, PLACED_DATE))
            .primaryKey(PrimaryKey.of(DATE_ID))
            .orderBy(DATE_ID.asc());

    private static final OrderedColumnField<Span, StampedOrderEntity, String> STATUS =
            ColumnField.of(Span.class, ROOT, "status", String.class);
    private static final OrderedColumnField<Span, StampedOrderEntity, Instant> SPAN_PLACED = ColumnField.of(
            Span.class, ROOT, "placedAt", Instant.class, Timestamp.class, InstantTimestampConverter.INSTANCE);
    private static final OrderedColumnField<Span, StampedOrderEntity, Date> SPAN_PLACED_DATE = ColumnField.of(
            Span.class, ROOT, "placedAt", Date.class, Timestamp.class, DateTimestampConverter.INSTANCE);
    private static final AggregateField<Span, Instant> FIRST = Agg.min(SPAN_PLACED);
    private static final AggregateField<Span, Instant> LAST = Agg.max(SPAN_PLACED);
    private static final AggregateField<Span, Date> LAST_DATE = Agg.max(SPAN_PLACED_DATE);
    private static final AggregateField<Span, Long> DISTINCT = Agg.countDistinct(SPAN_PLACED);

    private static final ModelQuery.Builder<StampedOrderEntity, Object, Span> SPANS = ModelQuery
            .builder(ROOT, row -> new Span(row.get(STATUS), row.get(FIRST), row.get(LAST), row.get(LAST_DATE),
                    row.get(DISTINCT)))
            .columns(ColumnSet.of(STATUS, FIRST, LAST, LAST_DATE, DISTINCT))
            .groupBy(STATUS);

    /** A stored value with microseconds, later than every fixture order. */
    private static final Timestamp MOVED = Timestamp.valueOf("2031-05-06 07:08:09.123456");
    private static final long MOVED_ID = 42L;

    @TckTest
    void ac_col_13_each_built_in_reads_filters_sorts_and_keyset_pages_a_timestamp_with_sub_millisecond_values(
            TckDatabase db) {
        inRolledBackTransaction(db, (em, executor) -> {
            em.createNativeQuery("update orders set placed_at = ? where id = ?")
                    .setParameter(1, MOVED).setParameter(2, MOVED_ID).executeUpdate();
            AtInstant instant = single(list(executor, AT_INSTANT.where(f -> f.eq(ID, MOVED_ID))));
            AtDate date = single(list(executor, AT_DATE.where(f -> f.eq(DATE_ID, MOVED_ID))));
            Timestamp stored = date.raw();
            // The Date is the Timestamp read, nanoseconds and all; the Instant keeps them too.
            assertThat(date.placed()).isInstanceOf(Timestamp.class).isEqualTo(stored);
            assertThat(instant.placed()).isEqualTo(stored.toInstant());
            // A TIMESTAMP column keeps microseconds or, as MySQL's does, whole seconds; the TCK reads which (R-QA-03).
            assertThat(stored.getNanos()).isIn(0, 123_456_000);
            if (stored.getNanos() != 0) {
                // A Date of the same millisecond binds without the microseconds, so it matches nothing.
                assertThat(dateIds(executor, f -> f.eq(PLACED_DATE, new Date(stored.getTime())))).isEmpty();
            }

            // A value read back binds exactly what was read, through each built-in.
            Instant at = instant.placed();
            Date on = date.placed();
            assertThat(instantIds(executor, f -> f.eq(PLACED, at))).containsExactly(MOVED_ID);
            assertThat(instantIds(executor, f -> f.gt(PLACED, at.minusNanos(1_000)))).containsExactly(MOVED_ID);
            assertThat(instantIds(executor, f -> f.lte(PLACED, at).gte(PLACED, at))).containsExactly(MOVED_ID);
            assertThat(dateIds(executor, f -> f.eq(PLACED_DATE, on))).containsExactly(MOVED_ID);
            assertThat(dateIds(executor, f -> f.gt(PLACED_DATE, on))).isEmpty();
            assertThat(dateIds(executor, f -> f.between(PLACED_DATE, on, on))).containsExactly(MOVED_ID);

            // Sorted and keyset-paged by each built-in: every order once, the moved one at the far end.
            List<AtInstant> byInstant = new ArrayList<>();
            List<AtDate> byDate = new ArrayList<>();
            executor.export(AT_INSTANT.orderBy(PLACED.desc()).keyset().build(), ExportOptions.of(700),
                    page -> page, byInstant::add);
            executor.export(AT_DATE.orderBy(PLACED_DATE.asc()).keyset().build(), ExportOptions.of(700),
                    page -> page, byDate::add);
            assertThat(byInstant).hasSize(TckFixture.ORDERS).extracting(AtInstant::id).doesNotHaveDuplicates();
            assertThat(byInstant).isSortedAccordingTo(Comparator.comparing(AtInstant::placed).reversed());
            assertThat(byInstant.get(0).id()).isEqualTo(MOVED_ID);
            assertThat(byDate).hasSize(TckFixture.ORDERS).extracting(AtDate::id).doesNotHaveDuplicates();
            assertThat(byDate).isSortedAccordingTo(Comparator.comparing(AtDate::raw));
            assertThat(byDate.get(byDate.size() - 1).id()).isEqualTo(MOVED_ID);
        });
    }

    @TckTest
    void ac_agg_13_min_max_and_count_distinct_over_each_built_in_return_model_typed_values(TckDatabase db) {
        List<Span> spans = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "agg-13-ordered-converters", ds -> withExecutor(ds,
                executor -> spans.addAll(executor.list(SPANS.orderBy(STATUS.asc()).build(), Limit.unlimited()))));
        List<Object[]> expected = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> expected.addAll(em.createQuery("select o.status, min(o.placedAt), max(o.placedAt),"
                    + " count(distinct o.placedAt) from StampedOrderEntity o group by o.status order by o.status",
                    Object[].class).getResultList()));
        }

        assertThat(spans).hasSize(expected.size()).hasSizeGreaterThan(1);
        for (int i = 0; i < spans.size(); i++) {
            Span span = spans.get(i);
            Object[] want = expected.get(i);
            assertThat(span.status()).isEqualTo(want[0]);
            assertThat(span.first()).isEqualTo(((Timestamp) want[1]).toInstant());
            assertThat(span.last()).isEqualTo(((Timestamp) want[2]).toInstant());
            assertThat(span.lastDate()).isInstanceOf(Timestamp.class).isEqualTo(want[2]);
            assertThat(span.distinct()).isEqualTo(want[3]);
        }

        // having binds the model value through the converter; ordering by the aggregate orders by the attribute.
        List<Span> byLast = spans.stream().sorted(Comparator.comparing(Span::last).reversed()).toList();
        Instant secondLatest = byLast.get(1).last();
        List<Span> latest = new ArrayList<>();
        List<Span> ordered = new ArrayList<>();
        withExecutor(JoinTestSupport.dataSource(db), executor -> {
            latest.addAll(executor.list(SPANS.having(h -> h.gt(LAST, secondLatest)).build(), Limit.unlimited()));
            ordered.addAll(executor.list(SPANS.orderBy(LAST_DATE.desc())
                    .having(h -> h.lte(LAST_DATE, Date.from(secondLatest))).build(), Limit.unlimited()));
        });
        assertThat(latest).containsExactly(byLast.get(0));
        assertThat(ordered).isEqualTo(byLast.subList(1, byLast.size()));
    }

    private static <T> T single(List<T> values) {
        assertThat(values).hasSize(1);
        return values.get(0);
    }

    private static <M> List<M> list(ModelQueryExecutor<StampedOrderEntity> executor,
            ModelQuery.Builder<StampedOrderEntity, Long, M> query) {
        return executor.list(query.build(), Limit.unlimited());
    }

    private static List<Long> instantIds(ModelQueryExecutor<StampedOrderEntity> executor,
            UnaryOperator<Filters<AtInstant>> filters) {
        return list(executor, AT_INSTANT.where(filters)).stream().map(AtInstant::id).toList();
    }

    private static List<Long> dateIds(ModelQueryExecutor<StampedOrderEntity> executor,
            UnaryOperator<Filters<AtDate>> filters) {
        return list(executor, AT_DATE.where(filters)).stream().map(AtDate::id).toList();
    }

    private interface Work {
        void run(EntityManager em, ModelQueryExecutor<StampedOrderEntity> executor);
    }

    private static void inRolledBackTransaction(TckDatabase db, Work work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    work.run(em, ModelQueryExecutor.create(em, StampedOrderEntity.class, ModelQueryConfig.defaults()));
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<StampedOrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, StampedOrderEntity.class, ModelQueryConfig.defaults())));
        }
    }
}
