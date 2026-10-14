package com.rey.modelquery.tck.vnd.ins;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderArchiveEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Assumptions;

/**
 * An upsert whose conflict action's {@code where} reads a to-one by its id renders the foreign-key column, which the
 * executor accepts where the foreign key references the target's primary key (spec api/10 R-COL-29, D-136). The
 * refusal where it does not is {@code ToOneIdTest}'s, on every vendor; the accepted statement runs on PostgreSQL.
 */
class ToOneIdConflictTest {

    /** An archive row with its customer by id. */
    record Archived(Long id, String status, Long customer) {}

    private static final TableField<OrderArchiveEntity, OrderArchiveEntity> ARCHIVE =
            TableField.root(OrderArchiveEntity.class);
    private static final ColumnField<Archived, OrderArchiveEntity, Long> ID =
            ColumnField.of(Archived.class, ARCHIVE, "id", Long.class);
    private static final ColumnField<Archived, OrderArchiveEntity, Long> ORDER_ID =
            ColumnField.of(Archived.class, ARCHIVE, "orderId", Long.class);
    private static final ColumnField<Archived, OrderArchiveEntity, String> STATUS =
            ColumnField.of(Archived.class, ARCHIVE, "status", String.class);
    private static final ColumnField<Archived, OrderArchiveEntity, Long> CUSTOMER =
            ColumnField.of(Archived.class, ARCHIVE, "customer", Long.class);

    @TckTest
    void ac_col_34_a_conflict_where_reading_a_primary_key_reference_by_id_upserts_on_postgresql(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.POSTGRESQL, "the conflict where is run on PostgreSQL");
        var columns = InsertColumns.<Archived, OrderArchiveEntity>of(ARCHIVE).addKey(ID, Archived::id)
                .add(ORDER_ID, Archived::id).add(STATUS, Archived::status).add(CUSTOMER, Archived::customer);
        var first = ValuesInsert.builder(columns, Long.class, List.of(new Archived(9001L, "PAID", 3L))).build();
        var matching = ValuesInsert.builder(columns, Long.class, List.of(new Archived(9001L, "NEW", 3L)))
                .onConflict(ID).doUpdate(u -> u.setFromRow(STATUS).where(f -> f.eq(CUSTOMER, 3L))).anyUniqueKey()
                .build();
        var other = ValuesInsert.builder(columns, Long.class, List.of(new Archived(9001L, "LATE", 3L)))
                .onConflict(ID).doUpdate(u -> u.setFromRow(STATUS).where(f -> f.eq(CUSTOMER, 7L))).anyUniqueKey()
                .build();
        var stored = new ArrayList<String>();

        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    var executor = ModelQueryExecutor.create(em, OrderArchiveEntity.class,
                            ModelQueryConfig.defaults());
                    executor.insert(first);
                    executor.insert(matching);
                    stored.add(status(em));
                    executor.insert(other);
                    stored.add(status(em));
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }

        // The first conflict where matches the stored customer and rewrites the status, the second does not
        assertThat(stored).containsExactly("NEW", "NEW");
    }

    private static String status(jakarta.persistence.EntityManager em) {
        em.clear();
        return em.createQuery("select a.status from OrderArchiveEntity a where a.id = 9001", String.class)
                .getSingleResult();
    }
}
