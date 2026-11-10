package com.rey.modelquery.tck.harness;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;

/**
 * {@code part_orders} and {@code part_events}, the latter partitioned by month on {@code created_at}, on PostgreSQL
 * (declarative {@code PARTITION BY RANGE}) and MySQL ({@code PARTITION BY RANGE COLUMNS}). H2 has no partitioning, so
 * the tests that use these tables skip it. Kept out of the shared fixture: only the key-group tests read them.
 *
 * <p>Order {@code i} (1 to {@value #ORDERS}) is placed in month {@code (i - 1) % 6} of 2022 and has three events,
 * {@code 1000 i + k}, created in that month. The partitions are the eight months from 2022-01.
 */
public final class PartitionedTables {

    public static final int ORDERS = 12;
    public static final int MONTHS = 6;
    private static final YearMonth FIRST = YearMonth.of(2022, 1);
    private static final int PARTITIONS = 8;
    private static final Set<String> CREATED = new HashSet<>();
    private static final Pattern PG_PARTITION = Pattern.compile("part_events_p(\\d{6})");

    private PartitionedTables() {}

    /** Skips the test on H2, else creates and seeds the tables on {@code db} once. */
    public static synchronized void ensure(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.H2, "H2 has no partitioned tables");
        if (!CREATED.add(db.jdbcUrl())) {
            return;
        }
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("drop table if exists part_events");
            s.execute("drop table if exists part_orders");
            s.execute("create table part_orders (id bigint not null primary key, placed_at timestamp not null)");
            boolean pg = db.vendor() == TckVendor.POSTGRESQL;
            if (pg) {
                s.execute("create table part_events (id bigint not null, order_id bigint not null, "
                        + "created_at timestamp not null, payload varchar(40), primary key (id, created_at)) "
                        + "partition by range (created_at)");
                s.execute("create index part_events_order_ix on part_events (order_id)");
            }
            StringBuilder parts = new StringBuilder();
            for (int m = 0; m < PARTITIONS; m++) {
                if (pg) {
                    s.execute("create table part_events_p" + tag(m) + " partition of part_events for values from ('"
                            + month(m) + "-01') to ('" + month(m + 1) + "-01')");
                } else {
                    parts.append(m == 0 ? "" : ", ").append("partition p").append(tag(m))
                            .append(" values less than ('").append(month(m + 1)).append("-01')");
                }
            }
            if (!pg) {
                s.execute("create table part_events (id bigint not null, order_id bigint not null, "
                        + "created_at datetime not null, payload varchar(40), primary key (id, created_at), "
                        + "key part_events_order_ix (order_id)) partition by range columns (created_at) (" + parts
                        + ")");
            }
            seed(c);
            s.execute(pg ? "analyze part_events" : "analyze table part_events");
        } catch (SQLException e) {
            CREATED.remove(db.jdbcUrl());
            throw new IllegalStateException("Cannot create the partitioned tables on " + db, e);
        }
    }

    private static void seed(Connection c) throws SQLException {
        try (PreparedStatement order = c.prepareStatement("insert into part_orders values (?, ?)");
                PreparedStatement event = c.prepareStatement("insert into part_events values (?, ?, ?, 'p')")) {
            for (int i = 1; i <= ORDERS; i++) {
                LocalDateTime month = FIRST.plusMonths(monthOf(i)).atDay(1).atStartOfDay();
                order.setLong(1, i);
                order.setTimestamp(2, Timestamp.valueOf(month.plusHours(i)));
                order.executeUpdate();
                for (int k = 0; k < 3; k++) {
                    event.setLong(1, 1000L * i + k);
                    event.setLong(2, i);
                    event.setTimestamp(3, Timestamp.valueOf(month.plusDays(i).plusHours(k)));
                    event.executeUpdate();
                }
            }
        }
    }

    /** The month, from 0, order {@code order} is placed in and its events are created in. */
    public static int monthOf(long order) {
        return (int) ((order - 1) % MONTHS);
    }

    /** The first instant of month {@code m} from 2022-01. */
    public static LocalDateTime start(int m) {
        return FIRST.plusMonths(m).atDay(1).atStartOfDay();
    }

    /**
     * The partitions a statement reads, from {@code EXPLAIN} of its text with {@code binds} written in as literals:
     * the partition names in PostgreSQL's plan, the {@code partitions} column of MySQL's.
     */
    public static List<String> partitionsRead(TckDatabase db, String sql, List<Object> binds) {
        StringBuilder literal = new StringBuilder();
        int next = 0;
        for (char ch : sql.toCharArray()) {
            literal.append(ch == '?' ? literal(binds.get(next++)) : String.valueOf(ch));
        }
        List<String> found = new ArrayList<>();
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("explain " + literal)) {
            while (rs.next()) {
                if (db.vendor() == TckVendor.POSTGRESQL) {
                    Matcher m = PG_PARTITION.matcher(rs.getString(1));
                    while (m.find()) {
                        if (!found.contains(m.group(1))) {
                            found.add(m.group(1));
                        }
                    }
                } else if (rs.getString("partitions") != null) {
                    for (String p : rs.getString("partitions").split(",")) {
                        found.add(p.substring(1));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot explain " + literal, e);
        }
        return found.stream().distinct().sorted().toList();
    }

    private static String literal(Object bind) {
        if (bind instanceof Number) {
            return bind.toString();
        }
        if (bind instanceof Timestamp t) {
            return "'" + t.toLocalDateTime().toString().replace('T', ' ') + "'";
        }
        return "'" + bind.toString().replace('T', ' ') + "'";
    }

    private static String month(int m) {
        return FIRST.plusMonths(m).toString();
    }

    private static String tag(int m) {
        return month(m).replace("-", "");
    }
}
