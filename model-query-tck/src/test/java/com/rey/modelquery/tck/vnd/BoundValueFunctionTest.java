package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.ConstantRowEntity;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Assumptions;

/**
 * {@code Expr.value} as a function's argument, on the vendor whose functions can only be named here: PostgreSQL's
 * {@code timezone(?, created_at)} and {@code jsonb_exists(tags, ?)} (spec api/10 R-COL-26; TCK AC-COL-31). The
 * snapshots show the bound argument as {@code ?}, where the same value through {@code Expr.constant} is text
 * (api/10 AC-COL-30).
 */
class BoundValueFunctionTest {

    /** A customer's id and its {@code created_at} read through {@code timezone('UTC', timezone(?, ...))}. */
    record UtcHour(Long id, LocalDateTime hour) {}

    /** A {@code constant_rows} row and whether its jsonb tags hold the bound key. */
    record Tagged(Long id, Boolean vip) {}

    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final ColumnField<UtcHour, CustomerEntity, Long> HOUR_ID =
            ColumnField.of(UtcHour.class, CUSTOMERS, "id", Long.class);
    private static final ColumnField<UtcHour, CustomerEntity, LocalDateTime> CREATED_AT =
            ColumnField.of(UtcHour.class, CUSTOMERS, "createdAt", LocalDateTime.class);
    private static final AggregateField<UtcHour, Long> HOUR_CUSTOMERS = Agg.count(CUSTOMERS);

    private static final TableField<ConstantRowEntity, ConstantRowEntity> CONSTANT_ROWS =
            TableField.root(ConstantRowEntity.class);
    private static final ColumnField<Tagged, ConstantRowEntity, Long> TAG_ID =
            ColumnField.of(Tagged.class, CONSTANT_ROWS, "id", Long.class);
    private static final ColumnField<Tagged, ConstantRowEntity, String> TAGS =
            ColumnField.of(Tagged.class, CONSTANT_ROWS, "tags", String.class);

    /** {@code jsonb_exists(tags, ?)}: the key is a bound argument, not the text {@code Expr.constant} would inline. */
    private static final ExpressionField<Tagged, Boolean> HAS_VIP =
            Expr.function("jsonb_exists", Boolean.class, TAGS, Expr.value("vip"));

    // ---- AC-COL-31

    @TckTest
    void ac_col_31_timezone_with_a_bound_zone_groups_a_timestamp_on_postgresql(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.POSTGRESQL,
                "timezone(text, timestamp) is a PostgreSQL function");
        // The outer timezone('UTC', ...) makes the timestamptz a timestamp again, so the group key is a local time
        // that does not depend on the JVM or session zone (as AC-COL-30's constant argument does).
        ExpressionField<UtcHour, LocalDateTime> hour = Expr.function("timezone", LocalDateTime.class,
                Expr.constant("UTC"),
                Expr.function("timezone", LocalDateTime.class, Expr.value("Asia/Ho_Chi_Minh"), CREATED_AT));
        var query = ModelQuery.builder(CUSTOMERS, row -> new UtcHour(row.get(HOUR_ID), row.get(hour)))
                .select(SelectSet.of(HOUR_ID, hour))
                .where(f -> f.lte(HOUR_ID, 20L))
                .groupBy(HOUR_ID, hour)
                .orderBy(HOUR_ID.asc())
                .build();
        // The same shift natively, so the bound run's groups are compared against literal text. It runs on its own
        // connection, outside the captured work, so it is not one of the snapshot's statements.
        var expected = new ArrayList<>();
        inSession(JoinTestSupport.dataSource(db), em -> expected
                .addAll(em.createNativeQuery("select timezone('UTC', timezone('Asia/Ho_Chi_Minh', created_at))"
                        + " from customers where id <= 20 order by created_at").getResultList()));
        List<UtcHour> groups = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-timezone-group", ds -> inSession(ds, em ->
                groups.addAll(executor(em, CustomerEntity.class).list(query, Limit.unlimited()))));
        assertThat(groups).extracting(UtcHour::id).containsExactlyElementsOf(ids(1, 20));
        assertThat(groups).extracting(UtcHour::hour).doesNotContainNull();
        for (int i = 0; i < groups.size(); i++) {
            assertThat(hourText(groups.get(i).hour())).as("hour of customer %s", groups.get(i).id())
                    .isEqualTo(hourText(expected.get(i)));
        }
        // The parameter is bound: the snapshot shows timezone(?, created_at), never the zone's text.
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("timezone(?,ce1_0.created_at)").doesNotContain("'Asia/Ho_Chi_Minh'");
    }

    @TckTest
    void ac_col_31_jsonb_exists_takes_a_bound_key_on_postgresql(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.POSTGRESQL,
                "jsonb_exists(jsonb, text) is a PostgreSQL function");
        var query = ModelQuery.builder(CONSTANT_ROWS, row -> new Tagged(row.get(TAG_ID), row.get(HAS_VIP)))
                .select(SelectSet.of(TAG_ID, HAS_VIP))
                .where(f -> f.eq(HAS_VIP, Boolean.TRUE))
                .orderBy(TAG_ID.asc())
                .build();
        List<Tagged> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-jsonb-exists", ds -> inSession(ds, em ->
                rows.addAll(executor(em, ConstantRowEntity.class).list(query, Limit.unlimited()))));
        // The fixture's tags are {"vip"}, {"basic"}, {"vip","basic"}, NULL, {"basic"} and {"vip"} (rows 1..6).
        assertThat(rows).containsExactly(new Tagged(1L, true), new Tagged(3L, true), new Tagged(6L, true));
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("jsonb_exists(").contains("?").doesNotContain("'vip'");
    }

    // ---- helpers

    /** A shifted timestamp's text form, so the function's type and the native one compare the same. */
    private static String hourText(Object value) {
        if (value instanceof Timestamp stamp) {
            return stamp.toLocalDateTime().toString();
        }
        return value instanceof LocalDateTime dateTime ? dateTime.toString() : String.valueOf(value);
    }

    private static List<Long> ids(long first, long last) {
        return LongStream.rangeClosed(first, last).boxed().toList();
    }

    private static <E> ModelQueryExecutor<E> executor(EntityManager em, Class<E> root) {
        return ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults());
    }

    private static void inSession(DataSource ds, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(work::accept);
        }
    }
}
