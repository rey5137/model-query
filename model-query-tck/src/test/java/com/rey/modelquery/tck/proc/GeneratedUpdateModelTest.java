package com.rey.modelquery.tck.proc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckDatabases;
import com.rey.modelquery.tck.harness.TckTarget;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

/**
 * Generated change sets written to a database (spec api/14 R-WRT-02, R-WRT-04). They run on H2 alone, as the other
 * generated-code criteria do: what a write renders is covered per vendor by the hand-written models of the TCK.
 * Every write runs in a transaction that is rolled back.
 */
class GeneratedUpdateModelTest {

    private static final TckDatabase DB = TckDatabases.get(TckTarget.h2());

    /** A row of the fixture whose two nullable columns both hold a value. */
    private static final long ROW = 1L;

    @Test
    void r_wrt_02_a_generated_change_set_writes_a_column_set_to_null_and_leaves_an_unset_one_alone() {
        inRolledBackTransaction(em -> {
            Object[] before = row(em);
            assertThat(before).doesNotContainNull();

            long written = executor(em).update(QSortRowPatch.update(QSortRowPatch.changes().sortText(null))
                    .whereKey(ROW).build());

            assertThat(written).isEqualTo(1);
            assertThat(row(em)).containsExactly(before[0], null);
        });
    }

    @Test
    void ac_wrt_03_from_copies_nulls_into_the_row_and_refuses_the_key_column() {
        inRolledBackTransaction(em -> {
            Object[] before = row(em);
            var view = new SortRowPatch(ROW, null, "copied");

            Changes<SortRowPatch> copied =
                    SortRowPatchChanges.from(view, SelectSet.of(QSortRowPatch.SORT_INT, QSortRowPatch.SORT_TEXT));
            long written = executor(em).update(QSortRowPatch.update(copied).whereKey(ROW).build());

            assertThat(before).doesNotContainNull();
            assertThat(written).isEqualTo(1);
            assertThat(row(em)).containsExactly(null, "copied");
            assertThatExceptionOfType(ModelQueryDefinitionException.class)
                    .isThrownBy(() -> SortRowPatchChanges.from(view,
                            SelectSet.of(QSortRowPatch.ID, QSortRowPatch.SORT_TEXT)))
                    .satisfies(e -> assertThat(e.code()).isEqualTo(MqCode.MQ1607));
        });
    }

    /** {@link #ROW}'s {@code sort_int} and {@code sort_text}, as stored. */
    private static Object[] row(EntityManager em) {
        List<?> values = Arrays.asList((Object[]) em.createNativeQuery(
                "select sort_int, sort_text from nullable_sort_rows where id = " + ROW).getSingleResult());
        return new Object[] {values.get(0) == null ? null : ((Number) values.get(0)).intValue(), values.get(1)};
    }

    private static ModelQueryExecutor<NullableSortEntity> executor(EntityManager em) {
        return ModelQueryExecutor.create(em, NullableSortEntity.class, ModelQueryConfig.defaults());
    }

    private static void inRolledBackTransaction(Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(DB)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    work.accept(em);
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }
}
