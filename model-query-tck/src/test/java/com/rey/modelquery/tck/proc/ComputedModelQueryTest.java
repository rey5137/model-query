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
 * A generated model whose group key is a computed expression over {@code orders} and whose aggregate sums another
 * expression (AC-PROC-14). Unlike {@link GeneratedModelTest}, which runs on H2 alone, this runs on every target: a
 * binding expression key is the part of the rendering the vendors disagree on (R-COL-19).
 */
class ComputedModelQueryTest {

    @TckTest
    void ac_proc_14_a_computed_group_key_and_an_aggregate_over_an_expression_return_exact_rows(TckDatabase db)
            throws SQLException {
        var query = QOrderBandTotals.query()
                .select(QOrderBandTotals.GROUP_KEYS.with(QOrderBandTotals.DOUBLED))
                .orderBy(QOrderBandTotals.BAND.asc())
                .build();
        List<OrderBandTotals> hibernate = list(db, true, query);
        List<OrderBandTotals> plain = list(db, false, query);
        List<OrderBandTotals> expected = expected(db);

        // The four statuses of the fixture, ascending; status is never NULL, so the coalesce changes no value.
        assertThat(expected).extracting(OrderBandTotals::band)
                .containsExactly("CANCELLED", "NEW", "PAID", "SHIPPED");
        assertThat(hibernate).hasSameSizeAs(expected);
        assertThat(plain).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(hibernate.get(i).band()).isEqualTo(expected.get(i).band());
            assertThat(plain.get(i).band()).isEqualTo(expected.get(i).band());
            assertThat(hibernate.get(i).doubled()).isEqualByComparingTo(expected.get(i).doubled());
            assertThat(plain.get(i).doubled()).isEqualByComparingTo(expected.get(i).doubled());
        }
    }

    /** The query's rows with and without {@code model-query-hibernate} resolving the vendor (R-VND-04). */
    private static List<OrderBandTotals> list(
            TckDatabase db, boolean hibernate, ModelQuery<OrderEntity, ?, OrderBandTotals> query) {
        List<OrderBandTotals> rows = new ArrayList<>();
        JoinTestSupport.withExecutor(JoinTestSupport.sessionFactory(db), hibernate, OrderEntity.class,
                ModelQueryConfig.defaults(), executor -> rows.addAll(executor.list(query, Limit.unlimited())));
        return rows;
    }

    /** The same groups computed by the database directly, keyed by the expression's value. */
    private static List<OrderBandTotals> expected(TckDatabase db) throws SQLException {
        List<OrderBandTotals> rows = new ArrayList<>();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COALESCE(status, 'none'), SUM(total * 2) FROM orders"
                        + " GROUP BY COALESCE(status, 'none') ORDER BY 1")) {
            while (result.next()) {
                rows.add(new OrderBandTotals(result.getString(1), result.getBigDecimal(2)));
            }
        }
        return rows;
    }
}
