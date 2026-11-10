package com.rey.modelquery.spike.rfc0023;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.QueryParam;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collection;

/**
 * The child table: monthly partitions over 36 months, a snowflake-style {@code id} (epoch millis since 2020-01-01
 * shifted left 22 bits, plus a sequence) and {@code created_at} as the partition column. The JPA {@code @Id} is
 * {@code id} alone; the DDL primary key is {@code (id, created_at)} (MySQL needs the partition column in every
 * unique key), written natively, not by schema generation.
 */
final class Events {
    private Events() {}

    static final Instant EPOCH = Instant.parse("2020-01-01T00:00:00Z");
    static final long EPOCH_MS = EPOCH.toEpochMilli();
    static final YearMonth FIRST = YearMonth.of(2022, 1);
    static final int MONTHS = 36;

    @Entity(name = "Event")
    @Table(name = "event_log")
    public static class Event {
        @Id public Long id;
        @Column(name = "order_id") public Long orderId;
        @Column(name = "created_at") public Instant createdAt;
        public String payload;
    }

    record Row(Long id, Long orderId, Instant createdAt) {}

    /** The group value: a half-open created_at range. */
    record Range(Instant from, Instant to) {
        static Range monthOf(long id) {
            YearMonth m = YearMonth.from(instant(id).atZone(ZoneOffset.UTC));
            return month(m);
        }

        static Range month(YearMonth m) {
            return new Range(m.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    m.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        }
    }

    static final Range ALL = new Range(Instant.parse("1000-01-01T00:00:00Z"), Instant.parse("9999-12-31T00:00:00Z"));

    static Instant instant(long id) {
        return EPOCH.plusMillis(id >> 22);
    }

    static final QueryParam<Instant> FROM = QueryParam.of("createdFrom", Instant.class);
    static final QueryParam<Instant> TO = QueryParam.of("createdTo", Instant.class);

    private static final TableField<Event, Event> ROOT = TableField.root(Event.class);
    static final ColumnField<Row, Event, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);
    static final ColumnField<Row, Event, Long> ORDER_ID = ColumnField.of(Row.class, ROOT, "orderId", Long.class);
    static final ColumnField<Row, Event, Instant> CREATED_AT =
            ColumnField.of(Row.class, ROOT, "createdAt", Instant.class);

    static ModelQuery.Builder<Event, Long, Row> base() {
        return ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(ORDER_ID), row.get(CREATED_AT)))
                .select(SelectSet.of(ID, ORDER_ID, CREATED_AT)).primaryKey(PrimaryKey.of(ID));
    }

    /** Ungrouped definition: no range condition. */
    static final ModelQuery.Builder<Event, Long, Row> NONE = base();
    /** {@code created_at >= :from and created_at < :to}. */
    static final ModelQuery.Builder<Event, Long, Row> TWO_SIDED = base().where(f -> f
            .compare(CREATED_AT, Op.GTE, Expr.param(FROM)).compare(CREATED_AT, Op.LT, Expr.param(TO)));
    /** {@code created_at >= :from}. */
    static final ModelQuery.Builder<Event, Long, Row> LOWER =
            base().where(f -> f.compare(CREATED_AT, Op.GTE, Expr.param(FROM)));

    static ModelQuery<Event, Long, Row> childNone(Collection<Long> orderIds) {
        return base().where(f -> f.in(ORDER_ID, orderIds)).build();
    }

    static ModelQuery<Event, Long, Row> childTwoSided(Collection<Long> orderIds, Range r) {
        return base().where(f -> f.in(ORDER_ID, orderIds).compare(CREATED_AT, Op.GTE, Expr.param(FROM))
                .compare(CREATED_AT, Op.LT, Expr.param(TO))).bind(FROM, r.from()).bind(TO, r.to()).build();
    }

    static ModelQuery<Event, Long, Row> childLower(Collection<Long> orderIds, Range r) {
        return base().where(f -> f.in(ORDER_ID, orderIds).compare(CREATED_AT, Op.GTE, Expr.param(FROM)))
                .bind(FROM, r.from()).build();
    }

    // ---- data: row (month m, sequence n) is created at monthStart + n * step seconds; its id and order id follow

    static long idOf(Instant at, int seq) {
        return Duration.between(EPOCH, at).toMillis() * 4194304L + (seq % 4096);
    }

    static Instant createdAt(int month, int n, int rows) {
        return Range.month(FIRST.plusMonths(month)).from().plusSeconds((long) n * (2_400_000 / rows));
    }

    static long id(int month, int n, int rows) {
        return idOf(createdAt(month, n, rows), n);
    }

    /** The order the row belongs to: created 0 to 40 days before the row, truncated to the hour. */
    static long orderId(int month, int n, int rows) {
        long ms = Duration.between(EPOCH, createdAt(month, n, rows)).toMillis();
        long orderMs = (ms - (n % 5) * 864_000_000L) / 3_600_000L * 3_600_000L;
        return orderMs * 4194304L;
    }

    static void seed(Connection c, String vendor, int rows) throws SQLException {
        int step = 2_400_000 / rows;
        try (Statement s = c.createStatement()) {
            switch (vendor) {
                case "H2" -> {
                    s.execute("create table event_log (id bigint not null, order_id bigint not null, "
                            + "created_at timestamp with time zone not null, payload varchar(40), "
                            + "primary key (id, created_at))");
                    s.execute("create index event_log_order_ix on event_log (order_id)");
                    for (int m = 0; m < MONTHS; m++) {
                        s.execute("insert into event_log select id, ((ms - cast(mod(n, 5) as bigint) * 864000000) / 3600000 * 3600000)"
                                + " * 4194304, ts, 'p' from (select n, ts, ms, ms * 4194304 + mod(n, 4096) id from ("
                                + "select n, ts, cast(datediff('SECOND', timestamp with time zone '2020-01-01 00:00:00+00', ts) as bigint) * 1000 ms "
                                + "from (select x n, dateadd('SECOND', x * " + step + ", timestamp with time zone '"
                                + month(m) + "-01 00:00:00+00') ts from system_range(0, " + (rows - 1) + "))))");
                    }
                }
                case "POSTGRESQL" -> {
                    s.execute("set time zone 'UTC'");
                    s.execute("create table event_log (id bigint not null, order_id bigint not null, "
                            + "created_at timestamptz not null, payload varchar(40), primary key (id, created_at)) "
                            + "partition by range (created_at)");
                    for (int m = 0; m < MONTHS; m++) {
                        s.execute("create table event_log_p" + tag(m) + " partition of event_log for values from ('"
                                + month(m) + "-01 00:00:00+00') to ('" + month(m + 1) + "-01 00:00:00+00')");
                    }
                    for (int m = 0; m < MONTHS; m++) {
                        s.execute("insert into event_log select (ms * 4194304 + (n & 4095)), "
                                + "((ms - (n % 5)::bigint * 864000000) / 3600000 * 3600000) * 4194304, ts, 'p' from ("
                                + "select n, ts, (extract(epoch from ts) * 1000)::bigint - " + EPOCH_MS + " ms from ("
                                + "select n, timestamptz '" + month(m) + "-01 00:00:00+00' + n * interval '" + step
                                + " second' ts from generate_series(0, " + (rows - 1) + ") n) a) b");
                    }
                    s.execute("create index event_log_order_ix on event_log (order_id)");
                    s.execute("analyze event_log");
                }
                default -> {
                    s.execute("set session time_zone = '+00:00'");
                    s.execute("set session cte_max_recursion_depth = 100000");
                    for (String table : new String[] {"event_log", "event_log_td"}) {
                        StringBuilder parts = new StringBuilder();
                        for (int m = 0; m < MONTHS; m++) {
                            parts.append(m == 0 ? "" : ", ").append("partition p").append(tag(m)).append(" values less than (")
                                    .append(table.equals("event_log") ? "'" + month(m + 1) + "-01 00:00:00'"
                                            : "to_days('" + month(m + 1) + "-01')").append(")");
                        }
                        s.execute("create table " + table + " (id bigint not null, order_id bigint not null, "
                                + "created_at datetime(6) not null, payload varchar(40), primary key (id, created_at), "
                                + "key ix_order (order_id)) partition by range "
                                + (table.equals("event_log") ? "columns(created_at)" : "(to_days(created_at))")
                                + " (" + parts + ")");
                    }
                    s.execute("create temporary table nums (n int primary key)");
                    s.execute("insert into nums with recursive r(n) as (select 0 union all select n + 1 from r "
                            + "where n < " + (rows - 1) + ") select n from r");
                    for (int m = 0; m < MONTHS; m++) {
                        s.execute("insert into event_log select ms * 4194304 + mod(n, 4096), "
                                + "((ms - mod(n, 5) * 864000000) div 3600000) * 3600000 * 4194304, ts, 'p' from ("
                                + "select n, ts, (unix_timestamp(ts) * 1000 - " + EPOCH_MS + ") ms from ("
                                + "select n, timestamp('" + month(m) + "-01 00:00:00') + interval n * " + step
                                + " second ts from nums) a) b");
                    }
                    s.execute("insert into event_log_td select * from event_log");
                    s.execute("analyze table event_log, event_log_td");
                }
            }
        }
    }

    private static String month(int m) {
        return FIRST.plusMonths(m).toString();
    }

    private static String tag(int m) {
        return month(m).replace("-", "");
    }
}
