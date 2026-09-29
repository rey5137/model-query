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
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("@COLLATE@", vendor.textCollation());
            return script.split(";\\s*\\n");
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
        insert(c, "INSERT INTO orders (id, customer_id, status, total, placed_at) VALUES (?,?,?,?,?)", ORDERS, (ps, i) -> {
            ps.setLong(1, i);
            ps.setLong(2, (i * 37L) % CUSTOMERS + 1);
            ps.setString(3, STATUSES[i % STATUSES.length]);
            ps.setBigDecimal(4, BigDecimal.valueOf((i * 1317L) % 100_000, 2));
            ps.setTimestamp(5, Timestamp.valueOf(BASE.plusMinutes(i * 17L)));
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
