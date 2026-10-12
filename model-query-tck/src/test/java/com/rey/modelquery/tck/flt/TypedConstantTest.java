package com.rey.modelquery.tck.flt;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.ConstantRank;
import com.rey.modelquery.tck.col.ConstantRowEntity;
import com.rey.modelquery.tck.col.ConstantStatus;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * Literal operands: {@code in}/{@code notIn} over operand fields, whose values are definition text, and
 * {@code Expr.constant(typedBy, value)}, which renders a column-typed constant through the provider's constant
 * renderer (spec api/12 R-FLT-19, api/10 R-COL-27; TCK AC-FLT-20, AC-COL-32).
 */
class TypedConstantTest {

    record R(Long id, ConstantStatus status, ConstantRank rank, Boolean active, String label) {}

    static final TableField<ConstantRowEntity, ConstantRowEntity> ROWS =
            TableField.root(ConstantRowEntity.class);
    static final ColumnField<R, ConstantRowEntity, Long> ID = ColumnField.of(R.class, ROWS, "id", Long.class);
    static final ColumnField<R, ConstantRowEntity, ConstantStatus> STATUS =
            ColumnField.of(R.class, ROWS, "status", ConstantStatus.class);
    static final ColumnField<R, ConstantRowEntity, ConstantRank> RANK =
            ColumnField.of(R.class, ROWS, "rank", ConstantRank.class);
    static final ColumnField<R, ConstantRowEntity, Boolean> ACTIVE =
            ColumnField.of(R.class, ROWS, "active", Boolean.class);
    static final ColumnField<R, ConstantRowEntity, String> LABEL =
            ColumnField.of(R.class, ROWS, "label", String.class);
    /** The label column through a {@link ColumnConverter}, so the constant is its trimmed stored text (R-COL-14). */
    static final ColumnField<R, ConstantRowEntity, String> TRIMMED_LABEL =
            ColumnField.of(R.class, ROWS, "label", String.class, String.class, new TrimmedLabel());

    /** The STRING enum's members as column-typed constants, inlined as their names (R-COL-27). */
    static final ExpressionField<R, ConstantStatus> PENDING = Expr.constant(STATUS, ConstantStatus.PENDING);
    static final ExpressionField<R, ConstantStatus> FAILED = Expr.constant(STATUS, ConstantStatus.FAILED);
    static final ExpressionField<R, ConstantStatus> CLOSED = Expr.constant(STATUS, ConstantStatus.CLOSED);

    private static final RowMapper<R> MAPPER = row -> new R(row.get(ID), row.get(STATUS), row.get(RANK),
            row.get(ACTIVE), row.get(LABEL));
    static final ModelQuery.Builder<ConstantRowEntity, Object, R> ROWS_QUERY = ModelQuery.builder(ROWS, MAPPER)
            .select(SelectSet.of(ID, STATUS, RANK, ACTIVE, LABEL))
            .orderBy(ID.asc());

    /** Trims only: the model value of the {@code label} column is stored padded, so a constant is its trimmed text. */
    static final class TrimmedLabel implements ColumnConverter<String, String> {
        @Override
        public String toModel(String attribute) {
            return attribute;
        }

        @Override
        public String toAttribute(String model) {
            return model.trim();
        }
    }

    // ---- AC-FLT-20

    @TckTest
    void ac_flt_20_in_and_not_in_over_operand_fields_inline_the_literals_and_keep_null_rows(TckDatabase db) {
        List<List<R>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "flt-20-in-fields", ds -> {
            results.add(rows(ds, f -> f.in(STATUS, PENDING, FAILED)));
            results.add(rows(ds, f -> f.in(STATUS, PENDING, FAILED, CLOSED)));
            results.add(rows(ds, f -> f.notIn(STATUS, PENDING, CLOSED)));
        });
        // Rows 1, 2, 5 and 6 are PENDING or FAILED; row 4's status is NULL (the fixture's constant_rows seed).
        assertThat(results.get(0)).extracting(R::id).containsExactly(1L, 2L, 5L, 6L);
        assertThat(results.get(1)).extracting(R::id).containsExactly(1L, 2L, 3L, 5L, 6L);
        // notIn keeps the NULL row, as the value form does (R-FLT-04): rows 2, 6 and the NULL row 4.
        assertThat(results.get(2)).extracting(R::id).containsExactly(2L, 4L, 6L);
        // The operands are inlined definition text, not binds (R-FLT-19, R-COL-18).
        assertThat(sql.get(0)).contains("in ('PENDING','FAILED')").doesNotContain("?");
        assertThat(sql.get(2)).contains("not in ('PENDING','CLOSED')").contains("is null");
    }

    @TckTest
    void ac_flt_20_a_quote_and_a_backslash_in_an_operand_round_trip(TckDatabase db) {
        List<List<R>> results = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "flt-20-in-escaped", ds -> {
            results.add(rows(ds, f -> f.in(LABEL, Expr.constant(LABEL, "O'Brien"), Expr.constant(LABEL, "x"))));
            results.add(rows(ds, f -> f.in(LABEL, Expr.constant(LABEL, "back\\slash"), Expr.constant(LABEL, "y"))));
            results.add(rows(ds, f -> f.compare(LABEL, Op.EQ, Expr.constant(LABEL, "mixed'and\\slash"))));
        });
        assertThat(results.get(0)).extracting(R::label).containsExactly("O'Brien");
        assertThat(results.get(1)).extracting(R::label).containsExactly("back\\slash");
        assertThat(results.get(2)).extracting(R::label).containsExactly("mixed'and\\slash");
    }

    // ---- AC-COL-32

    @TckTest
    void ac_col_32_a_constant_filters_and_compares_through_each_mapping(TckDatabase db) {
        List<List<R>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-32-constant-mappings", ds -> {
            // A STRING enum: 'CLOSED' as a literal, which the NULL row's status does not match.
            results.add(rows(ds, f -> f.compare(STATUS, Op.NE, CLOSED)));
            // An ORDINAL enum: MID is stored as 1.
            results.add(rows(ds, f -> f.compare(RANK, Op.EQ, Expr.constant(RANK, ConstantRank.MID))));
            // A Boolean through an AttributeConverter: true is stored as 'Y'.
            results.add(rows(ds, f -> f.compare(ACTIVE, Op.EQ, Expr.constant(ACTIVE, true))));
            // A ColumnConverter: the padded model value is stored trimmed.
            results.add(rows(ds, f -> f.compare(TRIMMED_LABEL, Op.EQ, Expr.constant(TRIMMED_LABEL, " padded "))));
        });
        assertThat(results.get(0)).extracting(R::id).containsExactly(1L, 2L, 5L, 6L);
        assertThat(results.get(1)).extracting(R::id).containsExactly(2L, 5L);
        assertThat(results.get(2)).extracting(R::id).containsExactly(1L, 3L, 6L);
        assertThat(results.get(3)).extracting(R::id).containsExactly(5L);
        assertThat(sql.get(0)).contains("status<>'CLOSED'").doesNotContain("?");
        assertThat(sql.get(1)).contains("rank_no=1");
        assertThat(sql.get(2)).contains("active='Y'");
        assertThat(sql.get(3)).contains("label='padded'");
    }

    // ---- helpers

    /** The rows of {@code constant_rows} the filter {@code where} keeps, through the executor's constant renderer. */
    private static List<R> rows(DataSource ds, UnaryOperator<Filters<R>> where) {
        List<R> results = new ArrayList<>();
        withExecutor(ds, executor -> results.addAll(executor.list(ROWS_QUERY.where(where).build(), Limit.unlimited())));
        return results;
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<ConstantRowEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, ConstantRowEntity.class, ModelQueryConfig.defaults())));
        }
    }
}
