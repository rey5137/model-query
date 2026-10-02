package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.DateTimestampConverter;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.InstantTimestampConverter;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;

/** One entity attribute selected by several columns of one model, on every read path (api/10 R-COL-10). */
class SharedAttributeTest {

    record Stamp(Long id, Instant placed, Date placedOn, Timestamp stored) {}

    record Day(Instant placed, Date placedOn, Long orders) {}

    private static final TableField<StampedOrderEntity, StampedOrderEntity> ROOT =
            TableField.root(StampedOrderEntity.class);

    private static final ColumnField<Stamp, StampedOrderEntity, Long> ID =
            ColumnField.of(Stamp.class, ROOT, "id", Long.class);
    private static final ColumnField<Stamp, StampedOrderEntity, Instant> PLACED = ColumnField.of(
            Stamp.class, ROOT, "placedAt", Instant.class, Timestamp.class, InstantTimestampConverter.INSTANCE);
    private static final ColumnField<Stamp, StampedOrderEntity, Date> PLACED_ON = ColumnField.of(
            Stamp.class, ROOT, "placedAt", Date.class, Timestamp.class, DateTimestampConverter.INSTANCE);
    private static final ColumnField<Stamp, StampedOrderEntity, Timestamp> STORED =
            ColumnField.of(Stamp.class, ROOT, "placedAt", Timestamp.class);

    private static final ModelQuery.Builder<StampedOrderEntity, Long, Stamp> STAMPS = ModelQuery
            .builder(ROOT, row -> new Stamp(row.get(ID), row.get(PLACED), row.get(PLACED_ON), row.get(STORED)))
            .columns(ColumnSet.of(ID, PLACED, PLACED_ON, STORED))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(PLACED_ON.desc());

    private static final ColumnField<Day, StampedOrderEntity, Instant> DAY_PLACED = ColumnField.of(
            Day.class, ROOT, "placedAt", Instant.class, Timestamp.class, InstantTimestampConverter.INSTANCE);
    private static final ColumnField<Day, StampedOrderEntity, Date> DAY_PLACED_ON = ColumnField.of(
            Day.class, ROOT, "placedAt", Date.class, Timestamp.class, DateTimestampConverter.INSTANCE);
    private static final AggregateField<Day, Long> ORDERS = Agg.count(ROOT);

    private static final ModelQuery.Builder<StampedOrderEntity, Object, Day> DAYS = ModelQuery
            .builder(ROOT, row -> new Day(row.get(DAY_PLACED), row.get(DAY_PLACED_ON), row.get(ORDERS)))
            .columns(ColumnSet.of(DAY_PLACED, DAY_PLACED_ON, ORDERS))
            .groupBy(DAY_PLACED, DAY_PLACED_ON)
            .orderBy(DAY_PLACED.desc());

    private static final Comparator<Stamp> NEWEST_FIRST =
            Comparator.comparing(Stamp::stored).reversed().thenComparing(Stamp::id);

    @TckTest
    void ac_col_14_columns_sharing_an_attribute_each_read_it_through_list_page_keyset_count_and_stream(
            TckDatabase db) {
        withExecutor(db, (em, executor) -> {
            Map<Long, Timestamp> stored = new HashMap<>();
            em.createQuery("select o.id, o.placedAt from StampedOrderEntity o", Object[].class).getResultList()
                    .forEach(pair -> stored.put((Long) pair[0], (Timestamp) pair[1]));
            List<Stamp> listed = executor.list(STAMPS.build(), Limit.unlimited());
            assertThat(listed).hasSize(TckFixture.ORDERS).isSortedAccordingTo(NEWEST_FIRST);
            listed.forEach(stamp -> assertReadsOneValue(stamp, stored));

            // Keyset-paged across several pages, sorted by one column and read through the others.
            List<Stamp> keyset = new ArrayList<>();
            executor.export(STAMPS.keyset().build(), ExportOptions.of(700), page -> page, keyset::add);
            assertThat(keyset).isEqualTo(listed);
            List<Stamp> byInstant = new ArrayList<>();
            executor.export(STAMPS.orderBy(PLACED.asc()).keyset().build(), ExportOptions.of(700), page -> page,
                    byInstant::add);
            assertThat(byInstant).hasSize(TckFixture.ORDERS).extracting(Stamp::id).doesNotHaveDuplicates();
            assertThat(byInstant).isSortedAccordingTo(Comparator.comparing(Stamp::placed));
            byInstant.forEach(stamp -> assertReadsOneValue(stamp, stored));

            // Offset pages, plain and primary key first, and the count beside them.
            Slice<Stamp> page = executor.page(STAMPS.build(), PageSpec.of(3, 50), CountMode.COUNT);
            assertThat(page.content()).isEqualTo(listed.subList(150, 200));
            assertThat(page.total()).hasValue(TckFixture.ORDERS);
            Slice<Stamp> keyFirst = executor.page(STAMPS.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build(),
                    PageSpec.of(3, 50), CountMode.NO_COUNT);
            assertThat(keyFirst.content()).isEqualTo(page.content());
            assertThat(executor.count(STAMPS.build())).isEqualTo(TckFixture.ORDERS);

            List<Stamp> streamed = executor.stream(STAMPS.build(), Limit.of(25), rows -> rows.toList());
            assertThat(streamed).isEqualTo(listed.subList(0, 25));
        });
    }

    @TckTest
    void ac_col_14_group_keys_sharing_an_attribute_each_read_it(TckDatabase db) {
        withExecutor(db, (em, executor) -> {
            List<Day> days = executor.list(DAYS.build(), Limit.unlimited());
            assertThat(days).isNotEmpty().isSortedAccordingTo(Comparator.comparing(Day::placed).reversed());
            assertThat(days).allSatisfy(day -> assertThat(day.placedOn()).isEqualTo(Timestamp.from(day.placed())));
            assertThat(days.stream().mapToLong(Day::orders).sum()).isEqualTo(TckFixture.ORDERS);
            assertThat(executor.count(DAYS.build())).isEqualTo(days.size());
            List<Day> exported = new ArrayList<>();
            executor.export(DAYS.build(), ExportOptions.of(700), page -> page, exported::add);
            assertThat(exported).isEqualTo(days);
        });
    }

    private static void assertReadsOneValue(Stamp stamp, Map<Long, Timestamp> stored) {
        Timestamp want = stored.get(stamp.id());
        assertThat(stamp.stored()).isEqualTo(want);
        assertThat(stamp.placedOn()).isInstanceOf(Timestamp.class).isEqualTo(want);
        assertThat(stamp.placed()).isEqualTo(want.toInstant());
    }

    private interface Work {
        void run(EntityManager em, ModelQueryExecutor<StampedOrderEntity> executor);
    }

    private static void withExecutor(TckDatabase db, Work work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                // A cursor stream needs a transaction on some vendors (R-PRF-03); nothing is written.
                em.getTransaction().begin();
                try {
                    work.run(em, ModelQueryExecutor.create(em, StampedOrderEntity.class, ModelQueryConfig.defaults()));
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }
}
