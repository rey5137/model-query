package com.rey.modelquery.tck.sel;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.vnd.ins.InsPersistEntity;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;

/** {@code persist} returning a model with a {@code @Selected} set (spec api/14 R-WRT-48, processor/31 R-GEN-30). */
class SelectedPersistTest {

    record NewPersist(String code) {}

    private static final TableField<InsPersistEntity, InsPersistEntity> ROOT = QSelPersistView.ROOT;

    private static final InsertColumns<NewPersist, InsPersistEntity> COLUMNS =
            InsertColumns.<NewPersist, InsPersistEntity>of(ROOT)
                    .add(ColumnField.of(NewPersist.class, ROOT, "code", String.class), NewPersist::code);

    @TckTest
    void ac_gen_20_persist_returning_fills_the_set_with_the_columns_it_selected_a_null_one_included(TckDatabase db) {
        var returning = QSelPersistView.query().select(SelectSet.of(QSelPersistView.ID, QSelPersistView.CODE,
                QSelPersistView.REGION)).build();
        var persist = ModelPersist.of(COLUMNS, Long.class, new NewPersist("s1"));

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            p.forget();
            p.factory().inSession(em -> {
                em.getTransaction().begin();
                try {
                    SelPersistView view = ModelQueryExecutor.create(em, InsPersistEntity.class,
                            ModelQueryConfig.defaults()).persist(persist, returning);

                    assertThat(view.id()).isNotNull();
                    assertThat(view.code()).isEqualTo("s1");
                    // The database default is not read back, so region is NULL, and selected all the same.
                    assertThat(view.region()).isNull();
                    assertThat(view.selected()).isEqualTo(SelectSet.of(QSelPersistView.ID, QSelPersistView.CODE,
                            QSelPersistView.REGION));
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }
}
