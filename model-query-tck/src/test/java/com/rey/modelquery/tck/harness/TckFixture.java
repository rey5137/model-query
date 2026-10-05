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
    /** Rows of {@code string_key_products} (TCK AC-PAG-24). */
    public static final int STRING_KEY_PRODUCTS = 30;
    /** Rows of {@code embedded_key_items}, five regions of six (TCK AC-PAG-25). */
    public static final int EMBEDDED_KEY_ITEMS = 30;
    /** Rows of {@code sku_products}; every {@code sku_order_lines} row references one by sku (TCK AC-COL-15). */
    public static final int SKU_PRODUCTS = 12;
    public static final int SKU_ORDER_LINES = 40;
    /** Rows of {@code formula_products} and {@code formula_lines} (TCK AC-COL-16). */
    public static final int FORMULA_PRODUCTS = 8;
    public static final int FORMULA_LINES = 16;
    /**
     * Rows of {@code payment_orders} (TCK AC-FCH-14, D-114 item 8): each with a payer, payee and initiator pair and a
     * requestor pair that is NULL on every fourth row. The references are a pure function of the row number:
     * payer {@code (1, i % 2 + 1)}, payee {@code (1, i % 3 + 1)}, initiator {@code (2, 1)}, requestor either
     * {@code (3, i % 2 + 1)} or absent.
     */
    public static final int PAYMENT_ORDERS = 6;
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
        // A String key whose codes sort differently from their insertion order, with a shared prefix and mixed case
        // (AC-PAG-24): the 7-step permutation puts row 30 on code P-0001 and every fifth row on a lowercase p- code.
        insert(c, "INSERT INTO string_key_products (code, name, category, price) VALUES (?,?,?,?)",
                STRING_KEY_PRODUCTS, (ps, i) -> {
                    ps.setString(1, stringProductCode(i));
                    ps.setString(2, "Product " + pad(i, 2));
                    ps.setString(3, "cat-" + pad(i % 3, 2));
                    ps.setBigDecimal(4, BigDecimal.valueOf((i * 41L) % 1_000, 2));
                });
        // An @EmbeddedId of (region_code, seq_no): five regions of six, four labels so ties straddle every page
        // (AC-PAG-25).
        insert(c, "INSERT INTO embedded_key_items (region_code, seq_no, label, amount) VALUES (?,?,?,?)",
                EMBEDDED_KEY_ITEMS, (ps, i) -> {
                    ps.setString(1, "R-" + pad((i - 1) / 6 + 1, 2));
                    ps.setInt(2, (i - 1) % 6 + 1);
                    ps.setString(3, "label-" + pad(i % 4, 2));
                    ps.setBigDecimal(4, BigDecimal.valueOf((i * 17L) % 1_000, 2));
                });
        // A surrogate-keyed product and lines that reference it by its unique non-key sku (AC-COL-15).
        insert(c, "INSERT INTO sku_products (id, sku, name, price) VALUES (?,?,?,?)", SKU_PRODUCTS, (ps, i) -> {
            ps.setLong(1, i);
            ps.setString(2, "SKU-" + pad(i, 3));
            ps.setString(3, "Sku " + pad(i, 2));
            ps.setBigDecimal(4, BigDecimal.valueOf((i * 29L) % 1_000, 2));
        });
        insert(c, "INSERT INTO sku_order_lines (id, product_sku, quantity) VALUES (?,?,?)", SKU_ORDER_LINES,
                (ps, i) -> {
                    ps.setLong(1, i);
                    ps.setString(2, "SKU-" + pad((i * 5) % SKU_PRODUCTS + 1, 3));
                    ps.setInt(3, i % 5 + 1);
                });
        // A String-keyed product and lines joined through a computed key: upper(product_code) (AC-COL-16).
        insert(c, "INSERT INTO formula_products (code, name, price) VALUES (?,?,?)", FORMULA_PRODUCTS, (ps, i) -> {
            ps.setString(1, "F-" + pad(i, 2));
            ps.setString(2, "Formula " + pad(i, 2));
            ps.setBigDecimal(3, BigDecimal.valueOf((i * 23L) % 1_000, 2));
        });
        insert(c, "INSERT INTO formula_lines (id, product_code, quantity) VALUES (?,?,?)", FORMULA_LINES, (ps, i) -> {
            ps.setLong(1, i);
            ps.setString(2, "f-" + pad((i * 3) % FORMULA_PRODUCTS + 1, 2));
            ps.setInt(3, i % 4 + 1);
        });
        // A payment order's four actors, the requestor absent on every fourth row (AC-FCH-14).
        insert(c, "INSERT INTO payment_orders (id, payer_user_type, payer_user_id, payee_user_type, payee_user_id, "
                + "initiator_user_type, initiator_user_id, requestor_user_type, requestor_user_id) "
                + "VALUES (?,?,?,?,?,?,?,?,?)", PAYMENT_ORDERS, (ps, i) -> {
                    ps.setLong(1, i);
                    ps.setInt(2, 1);
                    ps.setLong(3, i % 2 + 1);
                    ps.setInt(4, 1);
                    ps.setLong(5, i % 3 + 1);
                    ps.setInt(6, 2);
                    ps.setLong(7, 1);
                    if (i % 4 == 0) {
                        ps.setNull(8, Types.INTEGER);
                        ps.setNull(9, Types.BIGINT);
                    } else {
                        ps.setInt(8, 3);
                        ps.setLong(9, i % 2 + 1);
                    }
                });
    }

    /** The code of {@code string_key_products} row {@code row} (1-based), a permutation with mixed case. */
    private static String stringProductCode(int row) {
        return (row % 5 == 0 ? "p-" : "P-") + pad((row * 7) % STRING_KEY_PRODUCTS + 1, 4);
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
