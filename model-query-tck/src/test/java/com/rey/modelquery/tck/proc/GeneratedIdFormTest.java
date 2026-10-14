package com.rey.modelquery.tck.proc;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.WriteStatement;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.hibernate.SessionFactory;

/**
 * A query model that names a to-one by its id on every Tier-1 vendor: it reads the foreign key (a {@code NULL} one
 * too), writes it through its generated change set and insert, and returns it from a write (spec processor/30
 * R-PROC-33, processor/31 R-GEN-19, R-GEN-21; D-136). Every write runs in a transaction that is rolled back.
 */
class GeneratedIdFormTest {

    /** The fixture's order without a referrer (a third have one: ids divisible by 3) and one with. */
    private static final long WITHOUT_REFERRER = 1L;
    private static final long WITH_REFERRER = 3L;
    private static final long NEW_ORDER = 9001L;

    private static boolean returnsRows(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            return VendorResolver.resolve(sf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile()
                    .returning(WriteStatement.UPDATE).isPresent();
        }
    }

    @TckTest
    void ac_gen_33_the_id_form_reads_writes_through_changes_and_inserts_and_returns_from_a_write(TckDatabase db) {
        boolean returnsRows = returnsRows(db);
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    var executor = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                    var read = QOrderReferrerIdRow.query().select(QOrderReferrerIdRow.ALL);

                    // The id is read through a LEFT join: a NULL foreign key reads back as null, no row is dropped
                    OrderReferrerIdRow none = executor.one(read.build(), WITHOUT_REFERRER).orElseThrow();
                    OrderReferrerIdRow some = executor.one(read.build(), WITH_REFERRER).orElseThrow();
                    assertThat(none.referrerId()).isNull();
                    assertThat(some.referrerId()).isNotNull();
                    assertThat(some.customerId()).isNotNull();

                    // from copies the id form, and the change set writes it by id
                    var edited = new OrderReferrerIdRow(WITHOUT_REFERRER, none.status(), none.total(),
                            none.placedAt(), none.customerId(), some.referrerId());
                    var changes = OrderReferrerIdRowChanges.from(edited, SelectSet.of(QOrderReferrerIdRow.REFERRER_ID));
                    long written = executor.update(QOrderReferrerIdRow.update(changes)
                            .whereKey(WITHOUT_REFERRER).build());
                    em.flush();
                    em.clear();
                    OrderReferrerIdRow after = executor.one(read.build(), WITHOUT_REFERRER).orElseThrow();
                    assertThat(written).isEqualTo(1);
                    assertThat(after.referrerId()).isEqualTo(some.referrerId());

                    // NULL clears the foreign key
                    executor.update(QOrderReferrerIdRow.update(new OrderReferrerIdRowChanges().referrerId(null))
                            .whereKey(WITH_REFERRER).build());
                    em.flush();
                    em.clear();
                    assertThat(executor.one(read.build(), WITH_REFERRER).orElseThrow().referrerId()).isNull();

                    // The insert writes the id form, a NULL one included
                    var placed = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
                    long inserted = executor.insert(QOrderReferrerIdRow.insert(List.of(
                            new OrderReferrerIdRow(NEW_ORDER, "NEW", new BigDecimal("1.50"), placed,
                                    some.customerId(), some.referrerId()),
                            new OrderReferrerIdRow(NEW_ORDER + 1, "NEW", new BigDecimal("2.50"), placed,
                                    some.customerId(), null))).build());
                    em.flush();
                    em.clear();
                    var created = executor.list(QOrderReferrerIdRow.query().select(QOrderReferrerIdRow.ALL)
                            .where(f -> f.gte(QOrderReferrerIdRow.ID, NEW_ORDER))
                            .orderBy(QOrderReferrerIdRow.ID.asc()).build(), Limit.unlimited());
                    assertThat(inserted).isEqualTo(2);
                    assertThat(created).extracting(OrderReferrerIdRow::referrerId)
                            .containsExactly(some.referrerId(), null);
                    assertThat(created).extracting(OrderReferrerIdRow::customerId)
                            .containsExactly(some.customerId(), some.customerId());

                    // A returning write sends the path referrer.id, which the provider maps to the foreign key
                    if (returnsRows) {
                        var returned = executor.updateReturning(QOrderReferrerIdRow.update(
                                new OrderReferrerIdRowChanges().status("MARKED")).where(f -> f.gte(
                                        QOrderReferrerIdRow.ID, NEW_ORDER)).build(),
                                QOrderReferrerIdRow.query().select(SelectSet.of(QOrderReferrerIdRow.ID,
                                        QOrderReferrerIdRow.REFERRER_ID)).build());
                        assertThat(returned).extracting(OrderReferrerIdRow::referrerId)
                                .containsExactlyInAnyOrder(some.referrerId(), null);
                    }
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }
}
