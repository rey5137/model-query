package com.rey.modelquery.tck.wrt;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Assignment;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.vnd.ins.InsPersistEntity;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import java.util.List;

/**
 * An update that sets a JPA-converted attribute to null binds the provider's null for that attribute, so the
 * converter's value for null reaches the column (spec api/14 R-WRT-01).
 */
class UpdateNullConvertedTest {

    /** The change-set model. */
    record Patch() {}

    private static final TableField<InsPersistEntity, InsPersistEntity> ROOT = TableField.root(InsPersistEntity.class);
    private static final ColumnField<Patch, InsPersistEntity, Long> ID =
            ColumnField.of(Patch.class, ROOT, "id", Long.class);
    private static final ColumnField<Patch, InsPersistEntity, String> NOTE =
            ColumnField.of(Patch.class, ROOT, "note", String.class);
    private static final ColumnField<Patch, InsPersistEntity, Boolean> FLAGGED =
            ColumnField.of(Patch.class, ROOT, "flagged", Boolean.class);

    private record One(ColumnField<Patch, ?, ?> column) implements Changes<Patch> {
        @Override
        public boolean isSet(ColumnField<Patch, ?, ?> c) {
            return c.equals(column);
        }

        @Override
        public Changes<Patch> unset(ColumnField<Patch, ?, ?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public List<Assignment<Patch, ?>> assignments() {
            return List.of(Assignment.ofNull(column));
        }
    }

    @TckTest
    void ac_wrt_01_null_on_a_jpa_converted_attribute_writes_what_the_converter_gives_null(TckDatabase db) {
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(new One(NOTE)).whereKey(1L).build();

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            p.jdbc("insert into ins_persist (id, code, note, flagged) values (1, 'u1', 'hello', 'Y')");
            p.forget();
            p.factory().inSession(em -> {
                em.getTransaction().begin();
                try {
                    long written = ModelQueryExecutor.create(em, InsPersistEntity.class, ModelQueryConfig.defaults())
                            .update(update);
                    assertThat(written).isEqualTo(1);
                    em.getTransaction().commit();
                } finally {
                    if (em.getTransaction().isActive()) {
                        em.getTransaction().rollback();
                    }
                }
            });

            // NoneForNull stores null as NONE; a plain SQL NULL here would be the bug
            assertThat(p.rows("select note from ins_persist where id = 1")).containsExactly("NONE");
        }
    }

    @TckTest
    void ac_wrt_01_null_on_a_jpa_converted_boolean_writes_a_sql_null(TckDatabase db) {
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(new One(FLAGGED)).whereKey(1L)
                .build();

        try (InsertProbes p = InsertProbes.withPersistRoots(db)) {
            p.jdbc("insert into ins_persist (id, code, note, flagged) values (1, 'u1', 'hello', 'Y')");
            p.forget();
            p.factory().inSession(em -> {
                em.getTransaction().begin();
                try {
                    ModelQueryExecutor.create(em, InsPersistEntity.class, ModelQueryConfig.defaults())
                            .update(update);
                    em.getTransaction().commit();
                } finally {
                    if (em.getTransaction().isActive()) {
                        em.getTransaction().rollback();
                    }
                }
            });

            assertThat(p.rows("select flagged from ins_persist where id = 1")).containsExactly("null");
        }
    }
}
