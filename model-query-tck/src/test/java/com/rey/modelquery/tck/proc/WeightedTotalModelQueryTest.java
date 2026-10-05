package com.rey.modelquery.tck.proc;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * A generated model whose aggregate sums an expression {@code WeightedTotal} builds from the model's own generated
 * constants: a {@code @FilterColumn} constant and a {@code @Computed} constant (R-GEN-27, R-PROC-21, R-PROC-22).
 *
 * <p>The M9 gate finding: the aggregate constant was emitted before the filter-only and computed constants, so the
 * definition read null during {@code <clinit>} and the class failed to load. This runs the class on every target, so
 * the ordering is exercised, not just the generated text.
 */
class WeightedTotalModelQueryTest {

    @TckTest
    void ac_proc_14_a_definition_reads_generated_filter_and_computed_constants_and_returns_exact_rows(TckDatabase db)
            throws SQLException {
        var query = QOrderWeightTotals.query()
                .select(QOrderWeightTotals.GROUP_KEYS.with(QOrderWeightTotals.WEIGHTED))
                .orderBy(QOrderWeightTotals.BAND.asc())
                .build();
        List<OrderWeightTotals> hibernate = list(db, true, query);
        List<OrderWeightTotals> plain = list(db, false, query);
        List<OrderWeightTotals> expected = expected(db);

        // The four statuses of the fixture, ascending; status is never NULL, so the coalesce changes no value.
        assertThat(expected).extracting(OrderWeightTotals::band)
                .containsExactly("CANCELLED", "NEW", "PAID", "SHIPPED");
        assertThat(hibernate).hasSameSizeAs(expected);
        assertThat(plain).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(hibernate.get(i).band()).isEqualTo(expected.get(i).band());
            assertThat(plain.get(i).band()).isEqualTo(expected.get(i).band());
            assertThat(hibernate.get(i).weighted()).isEqualByComparingTo(expected.get(i).weighted());
            assertThat(plain.get(i).weighted()).isEqualByComparingTo(expected.get(i).weighted());
        }
    }

    /** The query's rows with and without {@code model-query-hibernate} resolving the vendor (R-VND-04). */
    private static List<OrderWeightTotals> list(
            TckDatabase db, boolean hibernate, ModelQuery<OrderEntity, ?, OrderWeightTotals> query) {
        List<OrderWeightTotals> rows = new ArrayList<>();
        JoinTestSupport.withExecutor(JoinTestSupport.sessionFactory(db), hibernate, OrderEntity.class,
                ModelQueryConfig.defaults(), executor -> rows.addAll(executor.list(query, Limit.unlimited())));
        return rows;
    }

    /** {@code total + total * 2}, the expression the aggregate sums, grouped by {@code status} as the database sees it. */
    private static List<OrderWeightTotals> expected(TckDatabase db) throws SQLException {
        List<OrderWeightTotals> rows = new ArrayList<>();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COALESCE(status, 'none'), SUM(total + total * 2)"
                        + " FROM orders GROUP BY COALESCE(status, 'none') ORDER BY 1")) {
            while (result.next()) {
                rows.add(new OrderWeightTotals(result.getString(1), null, result.getBigDecimal(2)));
            }
        }
        return rows;
    }
}
