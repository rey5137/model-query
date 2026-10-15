package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * Sources read with reserved-word field names and with a converted key a {@code @Child} is matched on (spec api/17
 * R-SRC-10, R-SRC-12; D-146).
 */
class SourceExecutionExtrasTest {

    @TckTest
    void r_src_12_source_fields_named_like_reserved_words_run_on_every_vendor(TckDatabase db) throws SQLException {
        var feed = QReservedFeed.query(QReservedSource.source().build()).select(QReservedFeed.ALL)
                .orderBy(QReservedFeed.ID.asc()).build();
        List<ReservedFeed> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, ReservedSource.class,
                executor -> rows.addAll(executor.list(feed, Limit.of(10)))));

        assertThat(sql).isNotEmpty();
        assertThat(rows.stream().map(row -> row.id() + "|" + normalised(row.rank()) + "|" + row.year()).toList())
                .isEqualTo(rows(db, "select id, total, status from orders where id <= 10 order by id"));
    }

    @TckTest
    void r_src_10_a_child_keyed_by_a_converted_source_field_loads_its_rows(TckDatabase db) throws SQLException {
        var plan = FetchPlan.of(QCodeOrdersFeed.ALL).child(QCodeOrdersFeed.ORDERS, FetchPlan.of(QStatusOrder.ALL));
        var feed = QCodeOrdersFeed.query(QCodeSource.source().build()).orderBy(QCodeOrdersFeed.ID.asc())
                .fetch(plan).build();
        List<CodeOrdersFeed> rows = new ArrayList<>();
        withExecutor(db, CodeSource.class, executor -> rows.addAll(executor.list(feed, Limit.of(3))));

        assertThat(rows).hasSize(3);
        for (CodeOrdersFeed row : rows) {
            List<String> expected = rows(db, "select id, status from orders where status = '" + row.status().name()
                    + "' order by id");
            assertThat(expected).isNotEmpty();
            assertThat(row.orders()).isNotEmpty();
            assertThat(row.orders().stream().map(order -> order.id() + "|" + order.status()).sorted().toList())
                    .isEqualTo(expected.stream().sorted().toList());
        }
    }

    private static <E> void withExecutor(TckDatabase db, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        withExecutor(JoinTestSupport.dataSource(db), root, work);
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }

    private static List<String> rows(TckDatabase db, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                StringBuilder row = new StringBuilder();
                for (int column = 1; column <= columns; column++) {
                    row.append(column == 1 ? "" : "|").append(normalised(result.getObject(column)));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    private static String normalised(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
}
