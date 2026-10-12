package com.rey.modelquery.tck.vnd;

import static com.rey.modelquery.core.RenderOptions.portable;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Assumptions;

/**
 * A function takes its literal argument as text, so which function truncates a timestamp is the vendor's choice and
 * this package names it (api/10 AC-COL-30, R-COL-18).
 *
 * <p>{@code // DECIDE:} the slice brief put this case in {@code tck.col.ExpressionTest}; AC-QA-02's arch rule forbids
 * a class outside {@code tck.harness}, {@code tck.sql} and {@code tck.vnd} from naming a vendor, so it lives here.
 */
class FunctionArgumentTest {

    /** One truncated-timestamp group and its order count. */
    record DayGroup(Object day, Long orders) {}

    /** An order's id, its placed-at value and the same value read through a function with a constant argument. */
    record ZoneShift(Long id, LocalDateTime placedAt, LocalDateTime zoned) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<DayGroup, OrderEntity, LocalDateTime> DAY_PLACED_AT =
            ColumnField.of(DayGroup.class, ORDERS, "placedAt", LocalDateTime.class);
    private static final AggregateField<DayGroup, Long> DAY_ORDERS = Agg.count(ORDERS);
    private static final ColumnField<ZoneShift, OrderEntity, Long> ZONE_ID =
            ColumnField.of(ZoneShift.class, ORDERS, "id", Long.class);
    private static final ColumnField<ZoneShift, OrderEntity, LocalDateTime> ZONE_PLACED_AT =
            ColumnField.of(ZoneShift.class, ORDERS, "placedAt", LocalDateTime.class);

    @TckTest
    void ac_col_30_a_date_truncation_function_with_a_constant_argument_is_selected_and_grouped(TckDatabase db) {
        ExpressionField<DayGroup, ?> day = dayKey(db.vendor(), DAY_PLACED_AT);
        var query = ModelQuery.builder(ORDERS, row -> new DayGroup(row.get(day), row.get(DAY_ORDERS)))
                .select(SelectSet.of(day, DAY_ORDERS))
                .groupBy(day)
                .orderBy(day.asc())
                .build();
        List<DayGroup> groups = new ArrayList<>();
        List<Object[]> expected = new ArrayList<>();
        inSession(db, em -> {
            groups.addAll(run(em, query));
            expected.addAll(em.createNativeQuery(dayKeySql(db.vendor())).getResultList());
        });
        // The seed's orders are 17 minutes apart, so the truncated keys collapse them into whole days.
        assertThat(groups).hasSameSizeAs(expected).hasSize(60);
        assertThat(groups).allSatisfy(group -> assertThat(group.day()).isNotNull());
        for (int i = 0; i < groups.size(); i++) {
            assertThat(dayText(groups.get(i).day())).as("day key %d", i).isEqualTo(dayText(expected.get(i)[0]));
            assertThat(groups.get(i).orders()).isEqualTo(((Number) expected.get(i)[1]).longValue());
        }
    }

    /** The date-truncation expression each vendor offers, with its unit as an {@code Expr.constant} argument. */
    private static ExpressionField<DayGroup, ?> dayKey(TckVendor vendor,
            ColumnField<DayGroup, OrderEntity, LocalDateTime> placedAt) {
        return switch (vendor) {
            case POSTGRESQL, H2 -> Expr.function("date_trunc", LocalDateTime.class, Expr.constant("day"), placedAt);
            case MYSQL -> Expr.function("date_format", String.class, placedAt, Expr.constant("%Y-%m-%d"));
        };
    }

    /** The same truncation in each vendor's SQL, for the expected groups. */
    private static String dayKeySql(TckVendor vendor) {
        return switch (vendor) {
            case POSTGRESQL, H2 -> "select date_trunc('day', placed_at), count(*) from orders "
                    + "group by date_trunc('day', placed_at) order by 1";
            case MYSQL -> "select date_format(placed_at, '%Y-%m-%d'), count(*) from orders "
                    + "group by date_format(placed_at, '%Y-%m-%d') order by 1";
        };
    }

    /** A truncated key's text form, so the provider's type and the native one compare the same. */
    private static String dayText(Object value) {
        if (value instanceof Timestamp stamp) {
            return stamp.toLocalDateTime().toString();
        }
        return value instanceof LocalDateTime dateTime ? dateTime.toString() : String.valueOf(value);
    }

    @TckTest
    void ac_col_30_timezone_with_a_fixed_zone_shifts_a_timestamp_on_postgresql(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.POSTGRESQL,
                "timezone(text, timestamp) is a PostgreSQL function");
        // placed_at is a local time in Asia/Ho_Chi_Minh; the outer timezone('UTC', ...) turns the timestamptz back into
        // a UTC timestamp, so the result does not depend on the JVM or session zone.
        var zoned = Expr.function("timezone", LocalDateTime.class, Expr.constant("UTC"),
                Expr.function("timezone", LocalDateTime.class, Expr.constant("Asia/Ho_Chi_Minh"), ZONE_PLACED_AT));
        var query = ModelQuery.builder(ORDERS,
                        row -> new ZoneShift(row.get(ZONE_ID), row.get(ZONE_PLACED_AT), row.get(zoned)))
                .select(SelectSet.of(ZONE_ID, ZONE_PLACED_AT, zoned))
                .where(f -> f.lte(ZONE_ID, 5L))
                .orderBy(ZONE_ID.asc())
                .build();
        List<ZoneShift> rows = new ArrayList<>();
        var expected = new HashMap<Long, LocalDateTime>();
        inSession(db, em -> {
            rows.addAll(run(em, query));
            for (ZoneShift row : rows) {
                expected.put(row.id(), (LocalDateTime) em.createNativeQuery(
                                "select timezone('UTC', timezone('Asia/Ho_Chi_Minh', placed_at))"
                                        + " from orders where id = " + row.id(), LocalDateTime.class)
                        .getSingleResult());
            }
        });
        assertThat(rows).isNotEmpty();
        for (ZoneShift row : rows) {
            assertThat(row.zoned()).as("timezone of order %s", row.id())
                    .isEqualTo(expected.get(row.id()))
                    .isEqualTo(row.placedAt().minusHours(7));
        }
    }

    // ---- helpers

    private static <E, M> List<M> run(EntityManager em, ModelQuery<E, ?, M> q) {
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, portable());
        return em.createQuery(built.query()).getResultList().stream().map(built::map).toList();
    }

    private static void inSession(TckDatabase db, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(work::accept);
        }
    }
}
