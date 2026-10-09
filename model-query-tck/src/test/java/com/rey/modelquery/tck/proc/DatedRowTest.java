package com.rey.modelquery.tck.proc;

import static org.assertj.core.api.Assertions.assertThat;

import static com.rey.modelquery.core.RenderOptions.portable;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.DatedRowEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;

/**
 * A generated model whose {@code Date} fields mirror an entity's {@code Date} attributes, which Hibernate reports as
 * {@code java.sql.Date}, {@code Time} and {@code Timestamp}: it compiles, and reads, filters and pages (AC-COL-28).
 */
class DatedRowTest {

    @TckTest
    void ac_col_28_date_fields_over_date_time_and_timestamp_attributes_read_filter_and_page(TckDatabase db) {
        var rows = QDatedRowView.query().select(QDatedRowView.ALL).orderBy(QDatedRowView.BORN_ON.asc(), QDatedRowView.ID.asc());
        withExecutor(db, executor -> {
            List<DatedRowView> all = executor.list(rows.build(), Limit.unlimited());
            assertThat(all).hasSize(TckFixture.DATED_ROWS).allSatisfy(row -> {
                assertThat(row.bornOn()).isInstanceOf(java.sql.Date.class);
                assertThat(row.ringsAt()).isInstanceOf(Time.class);
                assertThat(row.loggedAt()).isInstanceOf(Timestamp.class);
            });
            assertThat(all).extracting(DatedRowView::id).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
            assertThat(all.get(0).bornOn()).hasToString("2020-01-01");
            assertThat(all.get(0).ringsAt()).hasToString("08:01:00");

            // A value filter binds the model's Date to each attribute.
            DatedRowView fifth = all.get(4);
            assertThat(executor.list(rows.where(f -> f.lte(QDatedRowView.BORN_ON, Optional.of(fifth.bornOn()))).build(),
                    Limit.unlimited())).isEqualTo(all.subList(0, 5));
            assertThat(executor.list(rows.where(f -> f.gt(QDatedRowView.RINGS_AT, Optional.of(fifth.ringsAt()))).build(),
                    Limit.unlimited())).isEqualTo(all.subList(5, 10));
            assertThat(executor.list(rows.where(f -> f.gte(QDatedRowView.LOGGED_AT, Optional.of(fifth.loggedAt()))).build(),
                    Limit.unlimited())).isEqualTo(all.subList(4, 10));

            // Keyset pages over the DATE column, cursors carrying java.sql.Date values.
            List<DatedRowView> exported = new ArrayList<>();
            executor.export(rows.orderBy(QDatedRowView.BORN_ON.desc()).keyset().build(), ExportOptions.of(3),
                    page -> page, exported::add);
            // Rows 7 and 8 share a day and straddle the third page boundary; the key breaks the tie descending.
            assertThat(exported).isEqualTo(all.stream().sorted(Comparator.comparing(DatedRowView::bornOn)
                    .thenComparing(DatedRowView::id).reversed()).toList());
        });
    }

    /** Plain {@code java.util.Date} values, built here rather than read back, bind to the DATE and TIMESTAMP columns. */
    @TckTest
    void ac_col_28_plain_date_values_filter_the_date_and_timestamp_columns(TckDatabase db) {
        var rows = QDatedRowView.query().select(QDatedRowView.ALL)
                .orderBy(QDatedRowView.ID.asc());
        withExecutor(db, executor -> {
            assertThat(ids(executor, rows.where(f -> f.lte(QDatedRowView.BORN_ON, Optional.of(midnight(3)))))).containsExactly(1L, 2L, 3L);
            assertThat(ids(executor, rows.where(f -> f.gte(QDatedRowView.BORN_ON, Optional.of(midnight(9)))))).containsExactly(9L, 10L);
            assertThat(ids(executor, rows.where(f -> f.eq(QDatedRowView.BORN_ON, Optional.of(midnight(7)))))).containsExactly(7L, 8L);
            assertThat(ids(executor, rows.where(f -> f.eq(QDatedRowView.BORN_ON, Optional.of(midnight(8)))))).isEmpty();

            // A millisecond-precision Date: row 6 is 123 microseconds past its hour, so it is above the hour but not below it.
            Date sixth = new Date(Timestamp.valueOf(LocalDateTime.of(2020, 1, 1, 6, 0)).getTime());
            assertThat(ids(executor, rows.where(f -> f.gte(QDatedRowView.LOGGED_AT, Optional.of(sixth)))))
                    .containsExactly(6L, 7L, 8L, 9L, 10L);
            assertThat(ids(executor, rows.where(f -> f.gt(QDatedRowView.LOGGED_AT, Optional.of(sixth)))))
                    .containsExactly(6L, 7L, 8L, 9L, 10L);
            assertThat(ids(executor, rows.where(f -> f.lt(QDatedRowView.LOGGED_AT, Optional.of(sixth)))))
                    .containsExactly(1L, 2L, 3L, 4L, 5L);
        });
    }

    /** A keyset export over the TIMESTAMP column loses and repeats no row, and keeps the microseconds. */
    @TckTest
    void ac_col_28_a_keyset_export_over_a_timestamp_column_keeps_every_row_and_its_nanoseconds(TckDatabase db) {
        var rows = QDatedRowView.query().select(QDatedRowView.ALL).orderBy(QDatedRowView.LOGGED_AT.asc()).keyset();
        withExecutor(db, executor -> {
            List<DatedRowView> exported = new ArrayList<>();
            executor.export(rows.build(), ExportOptions.of(2), page -> page, exported::add);
            assertThat(exported).extracting(DatedRowView::id).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
            assertThat(exported).allSatisfy(row -> assertThat(((Timestamp) row.loggedAt()).getNanos())
                    .as("row " + row.id()).isEqualTo(row.id() % 3 == 0 ? 123_000 : 0));
        });
    }

    /** An expression and an aggregate declared {@code Date} resolve over the provider's {@code java.sql} types. */
    @TckTest
    void ac_col_28_a_date_expression_and_a_date_aggregate_resolve(TckDatabase db) {
        TableField<DatedRowEntity, DatedRowEntity> root = TableField.root(DatedRowEntity.class);
        var id = ColumnField.of(View.class, root, "id", Long.class);
        var bornOn = ColumnField.of(View.class, root, "bornOn", Date.class);
        var coalesced = Expr.coalesce(bornOn, bornOn);
        assertThat(coalesced.type()).isEqualTo(Date.class);
        AggregateField<Date, Date> latest = Agg.of("latest", Date.class, (ctx, cb) -> cb.greatest(bornOn.path(ctx)));
        var query = ModelQuery.builder(root, (Row r) -> r.get(latest)).select(SelectSet.of(latest)).build();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                CriteriaQuery<Tuple> q = cb.createTupleQuery();
                Root<DatedRowEntity> from = q.from(DatedRowEntity.class);
                JoinContext ctx = JoinContext.of(from, cb);
                Expression<Long> idSel = id.expression(ctx);
                Expression<Date> coalescedSel = coalesced.expression(ctx);
                q.multiselect(idSel, coalescedSel).where(cb.equal(id.path(ctx), 7L));
                List<Tuple> found = em.createQuery(q).getResultList();
                assertThat(found).hasSize(1);
                assertThat(found.get(0).get(coalescedSel)).hasToString("2020-01-07");

                BuiltQuery<?> built = query.buildQuery(cb, Phase.MODEL, portable());
                var max = em.createQuery(built.query()).getResultList().stream().map(built.selection()::row).toList();
                assertThat(max).hasSize(1);
                assertThat(max.get(0).get(latest)).hasToString("2020-01-10");
            });
        }
    }

    static final class View {}

    private static Date midnight(int day) {
        return new Date(LocalDate.of(2020, 1, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    private static List<Long> ids(ModelQueryExecutor<DatedRowEntity> executor,
            ModelQuery.Builder<DatedRowEntity, Long, DatedRowView> query) {
        return executor.list(query.build(), Limit.unlimited()).stream().map(DatedRowView::id).toList();
    }

    private static void withExecutor(TckDatabase db, Consumer<ModelQueryExecutor<DatedRowEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, DatedRowEntity.class, ModelQueryConfig.defaults())));
        }
    }
}
