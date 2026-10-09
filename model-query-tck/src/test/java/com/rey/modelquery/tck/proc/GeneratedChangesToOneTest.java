package com.rey.modelquery.tck.proc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.math.BigDecimal;
import org.hibernate.SessionFactory;

/**
 * A {@code generateChanges} query model with a to-one column on every Tier-1 vendor: the change set leaves the column
 * out, so copying the other columns from a view writes them, and a row with a {@code NULL} foreign key still reads
 * (spec processor/31 R-GEN-21, D-124). Every write runs in a transaction that is rolled back.
 */
class GeneratedChangesToOneTest {

    /** The fixture's order without a referrer (a third have one: ids divisible by 3) and one with. */
    private static final long WITHOUT_REFERRER = 1L;
    private static final long WITH_REFERRER = 3L;

    @TckTest
    void ac_gen_28_from_writes_the_other_columns_and_a_null_foreign_key_reads_through_the_whole_entity(
            TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                    var read = QOrderReferrerPatch.query().select(QOrderReferrerPatch.ALL);

                    // The row is read, not dropped by an inner join to the missing referrer
                    OrderReferrerPatch none = executor.one(read.build(), WITHOUT_REFERRER).orElseThrow();
                    OrderReferrerPatch some = executor.one(read.build(), WITH_REFERRER).orElseThrow();
                    assertThat(none.referrer()).isNull();
                    assertThat(some.referrer()).isNotNull();

                    var edited = new OrderReferrerPatch(WITHOUT_REFERRER, "EDITED", new BigDecimal("12.50"), null);
                    Changes<OrderReferrerPatch> changes = OrderReferrerPatchChanges.from(edited,
                            SelectSet.of(QOrderReferrerPatch.STATUS, QOrderReferrerPatch.TOTAL));
                    long written = executor.update(QOrderReferrerPatch.update(changes)
                            .whereKey(WITHOUT_REFERRER).build());
                    em.flush();
                    em.clear();
                    OrderReferrerPatch after = executor.one(read.build(), WITHOUT_REFERRER).orElseThrow();

                    assertThat(written).isEqualTo(1);
                    assertThat(after.status()).isEqualTo("EDITED");
                    assertThat(after.total()).isEqualByComparingTo("12.50");
                    assertThat(after.referrer()).isNull();
                    // The to-one is read but never written
                    assertThatExceptionOfType(ModelQueryDefinitionException.class)
                            .isThrownBy(() -> OrderReferrerPatchChanges.from(edited,
                                    SelectSet.of(QOrderReferrerPatch.REFERRER)))
                            .satisfies(e -> assertThat(e.code()).isEqualTo(MqCode.MQ1607));
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }

    @TckTest
    void ac_gen_28_a_whole_entity_to_one_keeps_the_null_foreign_key_rows_in_a_filter_and_an_order(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                long total = em.createQuery("select count(o) from OrderEntity o", Long.class).getSingleResult();
                long nulls = em.createQuery("select count(o) from OrderEntity o where o.referrer is null",
                        Long.class).getSingleResult();

                var withoutReferrer = QOrderReferrerPatch.query().select(QOrderReferrerPatch.ALL)
                        .where(f -> f.isNull(QOrderReferrerPatch.REFERRER)).orderBy(QOrderReferrerPatch.ID.asc());
                var ordered = QOrderReferrerPatch.query().select(QOrderReferrerPatch.ALL)
                        .orderBy(QOrderReferrerPatch.REFERRER.asc(), QOrderReferrerPatch.ID.asc());
                var none = executor.list(withoutReferrer.build(), Limit.unlimited());
                var all = executor.list(ordered.build(), Limit.unlimited());

                assertThat(nulls).isPositive().isLessThan(total);
                assertThat(none).hasSize((int) nulls).allSatisfy(row -> assertThat(row.referrer()).isNull());
                // One join serves the select and the order: no row is lost, none doubled
                assertThat(all).hasSize((int) total);
                assertThat(all).extracting(OrderReferrerPatch::id).doesNotHaveDuplicates();
            });
        }
    }

    @TckTest
    void ac_gen_28_a_converted_to_one_column_reads_a_null_foreign_key_as_null_without_the_converter(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                long total = em.createQuery("select count(o) from OrderEntity o", Long.class).getSingleResult();

                // ColumnConverter is never given null: the converter would throw on the null entity
                var rows = executor.list(QOrderReferrerKey.query().select(QOrderReferrerKey.ALL)
                        .orderBy(QOrderReferrerKey.ID.asc()).build(), Limit.unlimited());

                assertThat(rows).hasSize((int) total);
                assertThat(rows.get((int) WITHOUT_REFERRER - 1).referrerKey()).isNull();
                assertThat(rows.get((int) WITH_REFERRER - 1).referrerKey()).isNotNull();
            });
        }
    }
}
