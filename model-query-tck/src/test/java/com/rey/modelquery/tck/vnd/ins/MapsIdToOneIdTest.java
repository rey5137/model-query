package com.rey.modelquery.tck.vnd.ins;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.List;

/**
 * A {@code @MapsId} {@code @OneToOne} read by its id: a read joins the parent {@code LEFT}, and a write's filter
 * renders the shared key column with no join (spec api/10 R-COL-29, D-136). The probe table starts empty.
 */
class MapsIdToOneIdTest {

    /** A row and its parent's id, which is its own. */
    record MapsIdRow(Long id, Long parentId) {}

    private static final TableField<InsMapsIdEntity, InsMapsIdEntity> ROOT = TableField.root(InsMapsIdEntity.class);
    private static final ColumnField<MapsIdRow, InsMapsIdEntity, Long> ID =
            ColumnField.of(MapsIdRow.class, ROOT, "id", Long.class);
    private static final ColumnField<MapsIdRow, InsMapsIdEntity, Long> PARENT =
            ColumnField.of(MapsIdRow.class, ROOT, "parent", Long.class);
    private static final ColumnField<MapsIdRow, InsMapsIdEntity, String> NOTE =
            ColumnField.of(MapsIdRow.class, ROOT, "note", String.class);

    @TckTest
    void ac_col_34_a_maps_id_one_to_one_reads_its_id_through_a_left_join_and_writes_by_the_shared_key(TckDatabase db) {
        try (InsertProbes probes = InsertProbes.open(db)) {
            ModelQuery<InsMapsIdEntity, Long, MapsIdRow> read = ModelQuery
                    .builder(ROOT, row -> new MapsIdRow(row.get(ID), row.get(PARENT)))
                    .select(SelectSet.of(ID, PARENT)).primaryKey(PrimaryKey.of(ID))
                    .where(f -> f.eq(PARENT, 1L)).build();
            var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(NOTE, "x")
                    .where(f -> f.eq(PARENT, 1L)).build();
            long[] written = new long[1];
            List<MapsIdRow> rows = probes.inTransaction(session -> {
                var executor = ModelQueryExecutor.create(session, InsMapsIdEntity.class, ModelQueryConfig.defaults());
                written[0] = executor.update(update);
                return executor.list(read, Limit.unlimited());
            });
            List<String> sql = SqlSnapshots.assertMatches(db, "col-34-maps-id",
                    probes.statements().stream().map(SqlSnapshots::normalize).toList());
            assertThat(rows).isEmpty();
            assertThat(written[0]).isZero();
            assertThat(sql).hasSize(2);
            assertThat(sql.get(0)).startsWith("update").doesNotContain("join");
            assertThat(sql.get(1)).contains("left join");
        }
    }
}
