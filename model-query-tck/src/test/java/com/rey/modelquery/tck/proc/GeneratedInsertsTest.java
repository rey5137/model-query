package com.rey.modelquery.tck.proc;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.vnd.ins.InsAssignedEntity;
import com.rey.modelquery.tck.vnd.ins.InsAssignedView;
import com.rey.modelquery.tck.vnd.ins.InsSourceEntity;
import com.rey.modelquery.tck.vnd.ins.InsStampedEntity;
import com.rey.modelquery.tck.vnd.ins.InsStampedView;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import com.rey.modelquery.tck.vnd.ins.QInsAssignedView;
import com.rey.modelquery.tck.vnd.ins.QInsStampedView;
import java.time.Instant;
import java.util.List;

/**
 * A {@code @QueryModel(generateInserts = true)} model on every Tier-1 vendor: it inserts, insert-selects and persists
 * rows of its root, and reads them back (spec processor/30 R-PROC-26, processor/31 R-GEN-34).
 */
class GeneratedInsertsTest {

    /** A column of the insert-select's source, {@code ins_source}. */
    record SourceRow(Long id, String code, String name) {}

    @TckTest
    void ac_gen_27_a_generate_inserts_query_model_inserts_insert_selects_persists_and_reads_its_rows_back(
            TckDatabase db) {
        TableField<InsSourceEntity, InsSourceEntity> source = TableField.root(InsSourceEntity.class);
        var copy = QInsAssignedView.insertFrom(source)
                .map(QInsAssignedView.ID, ColumnField.of(SourceRow.class, source, "id", Long.class))
                .map(QInsAssignedView.CODE, ColumnField.of(SourceRow.class, source, "code", String.class))
                .map(QInsAssignedView.NAME, ColumnField.of(SourceRow.class, source, "name", String.class))
                .all().build();

        try (InsertProbes p = InsertProbes.open(db)) {
            p.factory().inSession(em -> {
                em.getTransaction().begin();
                try {
                    var executor = ModelQueryExecutor.create(em, InsAssignedEntity.class, ModelQueryConfig.defaults());

                    // The version a row carries is not written: the provider seeds it
                    Long key = executor.persist(QInsAssignedView.persist(new InsAssignedView(10L, "p10", "P", 7)));
                    long inserted = executor.insert(QInsAssignedView.insert(List.of(
                            new InsAssignedView(20L, "v20", "V", null), new InsAssignedView(21L, "v21", "W", 9)))
                            .build());
                    long copied = executor.insert(copy);
                    em.flush();
                    em.clear();
                    var all = QInsAssignedView.query().select(QInsAssignedView.ALL)
                            .orderBy(QInsAssignedView.ID.asc()).build();
                    List<InsAssignedView> rows = executor.list(all, Limit.unlimited());

                    assertThat(key).isEqualTo(10L);
                    assertThat(inserted).isEqualTo(2);
                    assertThat(copied).isEqualTo(3);
                    assertThat(rows).containsExactly(
                            new InsAssignedView(1L, "s1", "S1", 0), new InsAssignedView(2L, "s2", "S2", 0),
                            new InsAssignedView(3L, "s3", "S3", 0), new InsAssignedView(10L, "p10", "P", 0),
                            new InsAssignedView(20L, "v20", "V", 0), new InsAssignedView(21L, "v21", "W", 0));
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }

    @TckTest
    void ac_wrt_40_an_excluded_default_column_gets_the_default_under_insert_and_the_constructor_value_under_persist(
            TckDatabase db) {
        Instant created = Instant.parse("2026-01-02T03:04:05Z");
        try (InsertProbes p = InsertProbes.withStampedRoot(db)) {
            p.factory().inSession(em -> {
                em.getTransaction().begin();
                try {
                    var executor = ModelQueryExecutor.create(em, InsStampedEntity.class, ModelQueryConfig.defaults());

                    // The status the rows carry is not written: the database default applies, and createdAt is set
                    long inserted = executor.insert(QInsStampedView.insert(List.of(
                            new InsStampedView(1L, "a", "IGNORED", null), new InsStampedView(2L, "b", null, null)))
                            .set(QInsStampedView.CREATED_AT, created).build());
                    // A persisted row is written as the no-arg constructor leaves the excluded columns
                    executor.persist(QInsStampedView.persist(new InsStampedView(3L, "c", "IGNORED", created)));
                    em.flush();
                    em.clear();
                    var all = QInsStampedView.query().select(QInsStampedView.ALL)
                            .orderBy(QInsStampedView.ID.asc()).build();
                    List<InsStampedView> rows = executor.list(all, Limit.unlimited());

                    assertThat(inserted).isEqualTo(2);
                    assertThat(rows).containsExactly(
                            new InsStampedView(1L, "a", "NEW", created), new InsStampedView(2L, "b", "NEW", created),
                            new InsStampedView(3L, "c", "CTOR", null));
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }
}
