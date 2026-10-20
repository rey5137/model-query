package com.rey.modelquery.tck.pag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.inheritance.PagingEntities;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/** Duplicate assigned ids on inheritance and ordinary roots (R-PAG-26). */
class DuplicateKeyPagingTest {
    private record DuplicateRow(Long id, String reference, List<DuplicateRow> children) {}

    @Entity(name = "PagingDuplicateView")
    @Table(name = "paging_duplicate_view")
    public static class DuplicateView {
        @Id public Long id;
        public String reference;
    }

    @TckTest
    void ac_pag_31_table_per_class_duplicate_ids_are_refused_by_key_identified_reads(TckDatabase db) {
        Configuration cfg = configuration(db).addAnnotatedClass(PagingEntities.TpPayment.class)
                .addAnnotatedClass(PagingEntities.TpCard.class).addAnnotatedClass(PagingEntities.TpBank.class)
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        try (SessionFactory factory = cfg.buildSessionFactory()) {
            factory.inTransaction(em -> {
                em.createNativeQuery("insert into PagingTpPayment (id) values (1), (2)").executeUpdate();
                em.createNativeQuery("insert into PagingTpCard (id, reference) values (3, 'card')").executeUpdate();
                em.createNativeQuery("insert into PagingTpBank (id, reference) values (3, 'bank')").executeUpdate();
            });
            factory.inSession(em -> check(em, PagingEntities.TpPayment.class));
        }
    }

    @TckTest
    void ac_pag_31_a_plain_view_with_duplicate_ids_has_the_same_guards(TckDatabase db) {
        Configuration cfg = configuration(db).addAnnotatedClass(DuplicateView.class);
        try (SessionFactory factory = cfg.buildSessionFactory()) {
            factory.inTransaction(em -> em.createNativeQuery("create view paging_duplicate_view as "
                    + "select 1 as id, 'first' as reference union all select 2, 'second' "
                    + "union all select 3, 'card' union all select 3, 'bank'").executeUpdate());
            try {
                factory.inSession(em -> check(em, DuplicateView.class));
            } finally {
                factory.inTransaction(em -> em.createNativeQuery("drop view paging_duplicate_view").executeUpdate());
            }
        }
    }

    private static <E> void check(EntityManager em, Class<E> entity) {
        TableField<E, E> root = TableField.root(entity);
        var id = ColumnField.of(DuplicateRow.class, root, "id", Long.class);
        // A base TABLE_PER_CLASS row has no reference attribute, so identity alone suffices for every read.
        var select = SelectSet.of(id);
        var base = ModelQuery.builder(root, row -> new DuplicateRow(row.get(id), null, List.of()))
                .select(select).primaryKey(PrimaryKey.of(id)).orderBy(id.asc());
        var duplicate = base.where(f -> f.eq(id, 3L));
        var q = duplicate.build();
        var keyed = duplicate.keyset().build();
        ModelQueryExecutor<E> executor = ModelQueryExecutor.create(em, entity, ModelQueryConfig.defaults());
        assertThat(executor.list(q, Limit.of(10))).extracting(DuplicateRow::id).containsExactly(3L, 3L);
        assertThat(executor.page(q, PageSpec.of(0, 2), CountMode.NO_COUNT).content())
                .extracting(DuplicateRow::id).containsExactly(3L, 3L);
        // A duplicate split across a boundary remains outside the partial guard (R-PAG-26).
        var single = executor.page(keyed, KeysetSpec.first(1));
        assertThat(single.content()).extracting(DuplicateRow::id).containsExactly(3L);
        assertThat(executor.page(keyed, KeysetSpec.after(single.nextCursor().orElseThrow(), 1)).content()).isEmpty();
        for (var exported : List.of(q, keyed)) {
            List<DuplicateRow> sink = new ArrayList<>();
            assertThat(executor.export(exported, ExportOptions.of(1), page -> page, sink::add)).isEqualTo(1);
            assertThat(sink).extracting(DuplicateRow::id).containsExactly(3L);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            duplicate(() -> executor.page(keyed, KeysetSpec.first(2)));
            duplicate(() -> executor.one(q, 3L));
            duplicate(() -> executor.byKeys(q, List.of(3L, 3L)));
            for (var exported : List.of(q, keyed)) {
                List<DuplicateRow> sink = new ArrayList<>();
                duplicate(() -> executor.export(exported, ExportOptions.of(2), page -> page, sink::add));
                assertThat(sink).isEmpty();
            }
        }
        // Every export page, including the primary-key-first branch beyond its threshold, is checked.
        for (boolean deep : List.of(false, true)) {
            var all = deep ? base.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build() : base.build();
            List<DuplicateRow> sink = new ArrayList<>();
            duplicate(() -> executor.export(all, ExportOptions.of(2), page -> page, sink::add));
            assertThat(sink).extracting(DuplicateRow::id).containsExactly(1L, 2L);
        }
        AtomicInteger enriched = new AtomicInteger();
        var enrichedPlan = FetchPlan.of(select).enrich(Enricher.of(rows -> {
            enriched.incrementAndGet();
            return rows;
        }));
        duplicate(() -> executor.list(duplicate.fetch(enrichedPlan).build(), Limit.of(10)));
        duplicate(() -> executor.page(base.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0))
                .fetch(enrichedPlan).build(), PageSpec.ofOffset(2, 2), CountMode.NO_COUNT));
        assertThat(enriched).hasValue(0);
        // Child rows stay deduplicated per parent, including when a nested plan enriches them (D-160).
        var child = child(id, base);
        var parentPlan = FetchPlan.of(select).child(child, enrichedPlan);
        var parents = executor.list(duplicate.fetch(parentPlan)
                .customize((spec, joins, query, cb, phase) -> query.distinct(true)).build(), Limit.of(10));
        assertThat(parents).hasSize(1);
        assertThat(parents.get(0).children()).extracting(DuplicateRow::id).containsExactly(3L);
        assertThat(enriched).hasValue(1);
    }

    private static <E> ChildField<DuplicateRow, DuplicateRow> child(ColumnField<DuplicateRow, E, Long> id,
            ModelQuery.Builder<E, Long, DuplicateRow> query) {
        return new ChildField<>() {
            @Override public String name() { return "children"; }
            @Override public ColumnField<DuplicateRow, ?, ?> key() { return id; }
            @Override public Optional<ColumnField<DuplicateRow, ?, ?>> foreignKey() { return Optional.of(id); }
            @Override public Optional<TableField<?, ?>> through() { return Optional.empty(); }
            @Override public boolean isToMany() { return true; }
            @Override public ModelQuery.Builder<?, ?, DuplicateRow> query() { return query; }
            @Override public DuplicateRow with(DuplicateRow parent, List<DuplicateRow> children) {
                return new DuplicateRow(parent.id(), parent.reference(), children);
            }
        };
    }

    private static void duplicate(ThrowingCallable read) {
        assertThatThrownBy(read).isInstanceOfSatisfying(ModelQueryExecutionException.class,
                failure -> assertThat(failure.code()).isEqualTo(MqCode.MQ2213))
                .hasMessage("MQ2213: DuplicateRow: more than one row has primary key 3");
    }

    private static Configuration configuration(TckDatabase db) {
        return new Configuration().setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password());
    }
}
