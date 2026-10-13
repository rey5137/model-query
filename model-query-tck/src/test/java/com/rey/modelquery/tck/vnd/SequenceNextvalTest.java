package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.ConstantRowEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Assumptions;

/**
 * {@code Expr.nextval} (spec api/10 R-COL-28; TCK AC-COL-33): the next value of the fixture's {@code tck_seq} read in a
 * select on H2 and PostgreSQL, where the dialect spells the call, and refused with {@code MQ1509} on MySQL, which has
 * no sequences, and in a filter, a having, an order and a group key everywhere.
 */
class SequenceNextvalTest {

    /** A {@code constant_rows} row and the sequence value read beside it. */
    record Numbered(Long id, Long next) {}

    private static final TableField<ConstantRowEntity, ConstantRowEntity> ROWS =
            TableField.root(ConstantRowEntity.class);
    private static final ColumnField<Numbered, ConstantRowEntity, Long> ID =
            ColumnField.of(Numbered.class, ROWS, "id", Long.class);
    private static final ExpressionField<Numbered, Long> NEXT = Expr.nextval("tck_seq");

    private static ModelQuery.Builder<ConstantRowEntity, ?, Numbered> read() {
        return ModelQuery.builder(ROWS, row -> new Numbered(row.get(ID), row.get(NEXT)))
                .select(SelectSet.of(ID, NEXT));
    }

    // ---- AC-COL-33

    @TckTest
    void ac_col_33_nextval_selects_a_distinct_increasing_value_per_row_on_h2_and_postgresql(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.MYSQL, "MySQL has no sequences");
        var query = read().orderBy(ID.asc()).build();
        List<Numbered> first = new ArrayList<>();
        List<Numbered> second = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-33-select", ds -> inSession(ds, em -> {
            first.addAll(executor(em).list(query, Limit.unlimited()));
            second.addAll(executor(em).list(query, Limit.unlimited()));
        }));
        assertThat(first).hasSize(6).extracting(Numbered::id).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(first).extracting(Numbered::next).doesNotContainNull().doesNotHaveDuplicates();
        // The sequence goes on from where the first read left it: no value is read twice.
        long highest = first.stream().mapToLong(Numbered::next).max().orElseThrow();
        assertThat(second).extracting(Numbered::next).allSatisfy(next -> assertThat(next).isGreaterThan(highest))
                .doesNotHaveDuplicates();
        assertThat(sql).hasSize(2);
        assertThat(sql.get(0)).containsIgnoringCase(db.vendor() == TckVendor.POSTGRESQL
                ? "nextval('tck_seq')" : "next value for tck_seq");
    }

    @TckTest
    void ac_col_33_nextval_is_mq1509_on_mysql_at_first_resolution_before_any_statement(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.MYSQL, "H2 and PostgreSQL have sequences");
        var query = read().orderBy(ID.asc()).build();
        var refusals = new ArrayList<Throwable>();
        List<String> sql = SqlSnapshots.capture(db, ds -> inSession(ds, em -> refusals.add(catchThrowable(
                () -> executor(em).list(query, Limit.unlimited())))));
        assertThat(sql).as("no statement ran").isEmpty();
        assertThat(refusals).hasSize(1);
        assertThat(refusals.get(0)).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1509))
                .hasMessageContaining("no sequence support");
    }

    @TckTest
    void ac_col_33_nextval_in_a_filter_a_having_an_order_or_a_group_key_is_mq1509(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.MYSQL, "MySQL refuses the sequence itself");
        var filtered = read().where(f -> f.gt(NEXT, 0L)).build();
        // Selected and filtered by: the filter must not take the select's rendering of the expression.
        var plusOne = Expr.plus(NEXT, 1L);
        var filteredBySelected = ModelQuery.builder(ROWS, row -> new Numbered(row.get(ID), row.get(plusOne)))
                .select(SelectSet.of(ID, plusOne)).where(f -> f.gt(plusOne, 0L)).build();
        var ordered = read().orderBy(NEXT.asc()).build();
        var grouped = ModelQuery.builder(ROWS, row -> new Numbered(row.get(ID), null))
                .select(SelectSet.of(ID)).groupBy(ID, NEXT).build();
        var highest = Agg.max(NEXT);
        var having = ModelQuery.builder(ROWS, row -> new Numbered(row.get(ID), row.get(highest)))
                .select(SelectSet.of(ID, highest)).groupBy(ID).having(h -> h.gt(highest, 0L)).build();
        for (var query : List.of(filtered, filteredBySelected, ordered, grouped, having)) {
            var refusals = new ArrayList<Throwable>();
            List<String> sql = SqlSnapshots.capture(db, ds -> inSession(ds, em -> refusals.add(catchThrowable(
                    () -> executor(em).list(query, Limit.unlimited())))));
            assertThat(sql).as("no statement ran").isEmpty();
            assertThat(refusals).hasSize(1);
            assertThat(refusals.get(0)).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1509))
                    .hasMessageContaining("no filter, having, order or group key");
        }
    }

    @TckTest
    void ac_col_33_a_name_that_is_not_an_identifier_is_mq1509_at_definition(TckDatabase db) {
        for (String name : List.of("tck seq", "tck;seq", "a.b.c", "", "1seq")) {
            assertThatThrownBy(() -> Expr.nextval(name))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1509));
        }
        assertThat(Expr.<Numbered>nextval("public.tck_seq").toString()).isEqualTo("nextval(public.tck_seq)");
    }

    // ---- helpers

    private static ModelQueryExecutor<ConstantRowEntity> executor(EntityManager em) {
        return ModelQueryExecutor.create(em, ConstantRowEntity.class, ModelQueryConfig.defaults());
    }

    private static void inSession(DataSource ds, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(work::accept);
        }
    }
}
