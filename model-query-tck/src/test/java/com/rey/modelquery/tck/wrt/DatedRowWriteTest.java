package com.rey.modelquery.tck.wrt;

import static com.rey.modelquery.tck.wrt.BulkWriteTest.inRolledBackTransaction;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.DatedRowEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** A {@code Date} set on a column the provider reports as a {@code java.sql} type (AC-COL-28, D-122). */
class DatedRowWriteTest {

    record Patch(Long id, Date bornOn) {}

    record Row(Long id) {}

    private static final TableField<DatedRowEntity, DatedRowEntity> DATED = TableField.root(DatedRowEntity.class);
    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<Patch, DatedRowEntity, Long> ID =
            ColumnField.of(Patch.class, DATED, "id", Long.class);
    private static final ColumnField<Patch, DatedRowEntity, Date> BORN_ON =
            ColumnField.of(Patch.class, DATED, "bornOn", Date.class);
    private static final ColumnField<Row, DatedRowEntity, Long> ROW_ID =
            ColumnField.of(Row.class, DATED, "id", Long.class);
    private static final ColumnField<Row, OrderEntity, Long> ORDER_ID =
            ColumnField.of(Row.class, ORDERS, "id", Long.class);

    @TckTest
    void ac_col_28_an_update_sets_a_date_column_to_a_plain_date(TckDatabase db) {
        var update = ModelUpdate.builder(DATED).primaryKey(PrimaryKey.of(ID))
                .set(BORN_ON, at(2021, 6, 15, 0, 0)).whereKey(3L).build();
        var stored = new ArrayList<String>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            assertThat(executor(em).update(update)).isEqualTo(1);
            for (Object row : em.createNativeQuery("select cast(born_on as char(10)) from dated_rows "
                    + "where id in (2, 3) order by id").getResultList()) {
                stored.add(String.valueOf(row));
            }
        });

        assertThat(stored).containsExactly("2020-01-02", "2021-06-15");
    }

    /**
     * An insert-select types each constant's parameter as its attribute's stored type, so a plain {@code Date} is
     * written to a DATE, a TIME and a TIMESTAMP column alike.
     */
    @TckTest
    void ac_col_28_an_insert_select_sets_plain_date_constants_on_date_time_and_timestamp_columns(TckDatabase db) {
        assertInsertsDates(db, false);
    }

    /** As above, key-first in chunks of one, through the per-chunk source select. */
    @TckTest
    void ac_col_28_a_chunked_insert_select_sets_plain_date_constants(TckDatabase db) {
        assertInsertsDates(db, true);
    }

    private static void assertInsertsDates(TckDatabase db, boolean chunked) {
        var draft = ModelInsert.select(InsertColumns.<Row, DatedRowEntity>of(DATED).addKey(ROW_ID, Row::id), ORDERS)
                .map(ROW_ID, ORDER_ID)
                .set(ColumnField.of(Row.class, DATED, "bornOn", Date.class), at(2021, 6, 15, 0, 0))
                .set(ColumnField.of(Row.class, DATED, "ringsAt", Date.class), at(1970, 1, 1, 7, 30))
                .set(ColumnField.of(Row.class, DATED, "loggedAt", Date.class), at(2021, 6, 15, 7, 30))
                .where(f -> f.lt(ColumnField.of(Row.class, ORDERS, "id", Long.class), 3L));
        var insert = chunked ? draft.chunked(ChunkOptions.size(1)).build() : draft.build();
        long[] written = new long[1];
        var stored = new ArrayList<String>();

        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> {
            em.createNativeQuery("delete from dated_rows").executeUpdate();
            written[0] = executor(em).insert(insert);
            for (Object row : em.createNativeQuery("select id, cast(born_on as char(10)), "
                    + "cast(rings_at as char(8)), cast(logged_at as char(16)) from dated_rows order by id")
                    .getResultList()) {
                stored.add(String.join("|", List.of(((Object[]) row)[0].toString(), ((Object[]) row)[1].toString(),
                        ((Object[]) row)[2].toString(), ((Object[]) row)[3].toString())));
            }
        });

        assertThat(written[0]).isEqualTo(2);
        assertThat(stored).containsExactly("1|2021-06-15|07:30:00|2021-06-15 07:30",
                "2|2021-06-15|07:30:00|2021-06-15 07:30");
    }

    private static Date at(int year, int month, int day, int hour, int minute) {
        return new Date(LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant()
                .toEpochMilli());
    }

    private static ModelQueryExecutor<DatedRowEntity> executor(jakarta.persistence.EntityManager em) {
        return ModelQueryExecutor.create(em, DatedRowEntity.class, ModelQueryConfig.defaults());
    }
}
