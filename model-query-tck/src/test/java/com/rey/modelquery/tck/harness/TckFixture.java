package com.rey.modelquery.tck.harness;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The shared fixture (spec delivery/60 §2): DDL plus a deterministic seed. Every value is a pure function of the row
 * number, with no clock and no random source, so every vendor holds identical rows.
 */
public final class TckFixture {

    public static final int CUSTOMERS = 1_000;
    public static final int ORDERS = 5_000;
    public static final int ORDER_ITEMS = 20_000;
    public static final int COMPOSITE_TENANTS = 20;
    public static final int COMPOSITE_ITEMS_PER_TENANT = 100;
    public static final int COMPOSITE_KEY_ITEMS = COMPOSITE_TENANTS * COMPOSITE_ITEMS_PER_TENANT;
    public static final int NULLABLE_SORT_ROWS = 3_000;
    /** Rows of {@code keyset_types}, one per cursor type (TCK AC-PAG-18). */
    public static final int KEYSET_TYPES = 1_000;
    public static final int LABELS = 10;
    /** Orders 1 to this one carry labels, many-to-many: one or two each, every label on many orders. */
    public static final int LABELED_ORDERS = 200;
    /**
     * The customer notes' emails, by note id: three notes of customer 1, one of customer 3, and one of customer 2 whose
     * email differs from the customer's only by case, which the column's case-insensitive collation matches.
     */
    public static final List<String> NOTE_EMAILS = List.of("customer0001@example.test", "customer0001@example.test",
            "customer0001@example.test", "customer0003@example.test", "CUSTOMER0002@EXAMPLE.TEST");

    private static final int BATCH = 1_000;
    private static final LocalDateTime BASE = LocalDateTime.of(2020, 1, 1, 0, 0);
    private static final String[] COUNTRIES = {"US", "GB", "DE", "FR", "JP", "VN", "BR", "AU"};
    private static final String[] STATUSES = {"NEW", "PAID", "SHIPPED", "CANCELLED"};

    private TckFixture() {}

    static void create(Connection connection, TckVendor vendor) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                for (String ddl : ddl(vendor)) {
                    statement.execute(ddl);
                }
            }
            seed(connection);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static String[] ddl(TckVendor vendor) {
        try (InputStream in = TckFixture.class.getResourceAsStream("/com/rey/modelquery/tck/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql not found");
            }
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("@COLLATE@", vendor.textCollation())
                    .replace("@CI_TEXT@", vendor.caseInsensitiveText())
                    .replace("@BINARY@", vendor.binaryType())
                    .replace("@MICRO_TS@", vendor.microTimestamp());
            List<String> statements = new ArrayList<>(vendor.setup());
            statements.addAll(List.of(script.split(";\\s*\\n")));
            return statements.toArray(String[]::new);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void seed(Connection c) throws SQLException {
        insert(c, "INSERT INTO customers (id, name, email, country, vip, created_at) VALUES (?,?,?,?,?,?)", CUSTOMERS, (ps, i) -> {
            ps.setLong(1, i);
            ps.setString(2, "Customer " + pad(i, 4));
            ps.setString(3, "customer" + pad(i, 4) + "@example.test");
            ps.setString(4, COUNTRIES[(i * 7) % COUNTRIES.length]);
            ps.setBoolean(5, i % 10 == 0);
            ps.setTimestamp(6, Timestamp.valueOf(BASE.plusHours(i)));
        });
        // A third of the orders have a referrer: a nullable to-one reference, where an INNER join removes rows.
        insert(c, "INSERT INTO orders (id, customer_id, status, total, placed_at, referrer_id) VALUES (?,?,?,?,?,?)", ORDERS, (ps, i) -> {
            ps.setLong(1, i);
            ps.setLong(2, (i * 37L) % CUSTOMERS + 1);
            ps.setString(3, STATUSES[i % STATUSES.length]);
            ps.setBigDecimal(4, BigDecimal.valueOf((i * 1317L) % 100_000, 2));
            ps.setTimestamp(5, Timestamp.valueOf(BASE.plusMinutes(i * 17L)));
            if (i % 3 == 0) {
                ps.setLong(6, (i * 11L) % CUSTOMERS + 1);
            } else {
                ps.setNull(6, Types.BIGINT);
            }
        });
        insert(c, "INSERT INTO order_items (id, order_id, product_code, quantity, unit_price) VALUES (?,?,?,?,?)", ORDER_ITEMS, (ps, i) -> {
            ps.setLong(1, i);
            ps.setLong(2, ((i - 1) * 7L) % ORDERS + 1);
            ps.setString(3, "P" + pad(i % 50, 3));
            ps.setInt(4, i % 9 + 1);
            ps.setBigDecimal(5, BigDecimal.valueOf((i * 31L) % 10_000 + 100, 2));
        });
        insert(c, "INSERT INTO composite_key_items (tenant_id, item_no, label, amount) VALUES (?,?,?,?)", COMPOSITE_KEY_ITEMS, (ps, i) -> {
            int tenant = (i - 1) / COMPOSITE_ITEMS_PER_TENANT + 1;
            int itemNo = (i - 1) % COMPOSITE_ITEMS_PER_TENANT + 1;
            ps.setInt(1, tenant);
            ps.setInt(2, itemNo);
            ps.setString(3, "label-" + pad(i % 25, 2));
            ps.setBigDecimal(4, BigDecimal.valueOf((i * 53L) % 5_000, 2));
        });
        // Nullable sort columns hold duplicates (i % 50) and NULLs on independent cycles (5, 7, 11).
        insert(c, "INSERT INTO nullable_sort_rows (id, sort_int, sort_text, sort_ts) VALUES (?,?,?,?)", NULLABLE_SORT_ROWS, (ps, i) -> {
            ps.setLong(1, i);
            if (i % 5 == 0) {
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setInt(2, i % 50);
            }
            if (i % 7 == 0) {
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setString(3, "t" + pad(i % 50, 2));
            }
            if (i % 11 == 0) {
                ps.setNull(4, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(4, Timestamp.valueOf(BASE.plusDays(i % 50)));
            }
        });
        insert(c, "INSERT INTO labels (id, name) VALUES (?,?)", LABELS, (ps, i) -> {
            ps.setLong(1, i);
            ps.setString(2, "label-" + pad(i, 2));
        });
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO order_labels (label_id, order_id) VALUES (?,?)")) {
            for (long order = 1; order <= LABELED_ORDERS; order++) {
                for (long label : labelsOf(order)) {
                    ps.setLong(1, label);
                    ps.setLong(2, order);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
        insert(c, "INSERT INTO customer_notes (id, customer_email, body) VALUES (?,?,?)", NOTE_EMAILS.size(),
                (ps, i) -> {
                    ps.setLong(1, i);
                    ps.setString(2, NOTE_EMAILS.get(i - 1));
                    ps.setString(3, "note " + i);
                });
        // Keyset cursor types (AC-PAG-18): ties of 20, a scale-4 decimal, microseconds, a UUID, a byte[] and a
        // converted value class, each a pure function of the row number.
        insert(c, "INSERT INTO keyset_types (id, tie, amount, stamp, token, payload, shape) VALUES (?,?,?,?,?,?,?)",
                KEYSET_TYPES, (ps, i) -> {
                    ps.setLong(1, i);
                    ps.setInt(2, i % 20);
                    ps.setBigDecimal(3, BigDecimal.valueOf((i * 37L) % 10_000, 4));
                    Timestamp stamp = Timestamp.valueOf(BASE.plusSeconds(i));
                    stamp.setNanos((i % 997) * 1_000);
                    ps.setTimestamp(4, stamp);
                    ps.setString(5, new UUID(0, i).toString());
                    ps.setBytes(6, new byte[] {(byte) (i >> 8), (byte) i});
                    ps.setString(7, "shape-" + (i % 5));
                });
    }

    /** The labels of order {@code order}, ascending: none past {@link #LABELED_ORDERS}. */
    public static List<Long> labelsOf(long order) {
        if (order > LABELED_ORDERS) {
            return List.of();
        }
        long first = order % LABELS + 1;
        long second = order * 3 % LABELS + 1;
        return first == second ? List.of(first) : List.of(Math.min(first, second), Math.max(first, second));
    }

    private interface RowBinder {
        void bind(PreparedStatement ps, int rowNumber) throws SQLException;
    }

    /** Inserts rows numbered 1..count. */
    private static void insert(Connection c, String sql, int count, RowBinder binder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 1; i <= count; i++) {
                binder.bind(ps, i);
                ps.addBatch();
                if (i % BATCH == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private static String pad(int value, int width) {
        return String.format("%0" + width + "d", value);
    }
}
