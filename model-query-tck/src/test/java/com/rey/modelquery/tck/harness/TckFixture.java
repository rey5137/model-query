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
import java.util.Arrays;
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
     * Rows of {@code deliveries} (TCK AC-FCH-14, D-114 item 8): each with a sender, recipient and courier pair and a
     * approver pair that is NULL on every fourth row. The references are a pure function of the row number:
     * sender {@code (1, i % 2 + 1)}, recipient {@code (1, i % 3 + 1)}, courier {@code (2, 1)}, approver either
     * {@code (3, i % 2 + 1)} or absent.
     */
    public static final int DELIVERIES = 6;
    /**
     * Rows of {@code dated_rows}: day {@code i} of 2020 (rows 7 and 8 share day 7), minute {@code i} after 08:00, hour
     * {@code i} (plus 123 microseconds on rows 3, 6 and 9) (AC-COL-28).
     */
    public static final int DATED_ROWS = 10;
    public static final int LABELS = 10;
    /** Rows of {@code constant_rows}, one per column-typed constant's mapping (TCK AC-FLT-20, AC-COL-32). */
    public static final int CONSTANT_ROWS = 6;
    /**
     * Rows of {@code card_holders} and {@code cards}: card {@code i} belongs to holder {@code i + 4} up to card 6, so
     * holders 1-4, 11 and 12 have none, and its issuer is NULL on every fourth card, a missing customer on cards 1 and
     * 5, else customer {@code i} (TCK AC-COL-34).
     */
    public static final int CARD_HOLDERS = 12;
    public static final int CARDS = 8;
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
    /**
     * The {@code constant_rows} seed, one entry per row: the STRING enum's name, the ORDINAL enum's ordinal, the
     * converted {@code Y}/{@code N} flag and the label. Row 4 is NULL everywhere, row 2's label holds a quote and
     * row 3's and 6's a backslash (TCK AC-FLT-20, AC-COL-32). Its {@code state} is the status's first letter.
     */
    private static final List<String> CONSTANT_STATUSES = Arrays.asList("PENDING", "FAILED", "CLOSED", null, "PENDING",
            "FAILED");
    private static final List<Integer> CONSTANT_RANKS = Arrays.asList(0, 1, 2, null, 1, null);
    private static final List<String> CONSTANT_ACTIVE = Arrays.asList("Y", "N", "Y", null, "N", "Y");
    private static final List<String> CONSTANT_LABELS = Arrays.asList("plain", "O'Brien", "back\\slash", null,
            "padded", "mixed'and\\slash");
    /** The {@code constant_rows.tags} JSON arrays: rows 1, 3 and 6 hold {@code "vip"} (TCK AC-COL-31). */
    private static final List<String> CONSTANT_TAGS = Arrays.asList("[\"vip\"]", "[\"basic\"]",
            "[\"vip\",\"basic\"]", null, "[\"basic\"]", "[\"vip\"]");

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
            seed(connection, vendor);
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
                    .replace("@MICRO_TS@", vendor.microTimestamp())
                    .replace("@JSONB@", vendor.jsonbType());
            List<String> statements = new ArrayList<>(vendor.setup());
            statements.addAll(List.of(script.split(";\\s*\\n")));
            return statements.toArray(String[]::new);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void seed(Connection c, TckVendor vendor) throws SQLException {
        insert(c, "INSERT INTO customers (id, name, email, country, vip, created_at) VALUES (?,?,?,?,?,?)",
                CUSTOMERS, (ps, i) -> {
            ps.setLong(1, i);
            ps.setString(2, "Customer " + pad(i, 4));
            ps.setString(3, "customer" + pad(i, 4) + "@example.test");
            ps.setString(4, COUNTRIES[(i * 7) % COUNTRIES.length]);
            ps.setBoolean(5, i % 10 == 0);
            ps.setTimestamp(6, Timestamp.valueOf(BASE.plusHours(i)));
        });
        // A third of the orders have a referrer: a nullable to-one reference, where an INNER join removes rows.
        insert(c, "INSERT INTO orders (id, customer_id, status, total, placed_at, referrer_id) VALUES (?,?,?,?,?,?)",
                ORDERS, (ps, i) -> {
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
        insert(c, "INSERT INTO order_items (id, order_id, product_code, quantity, unit_price) VALUES (?,?,?,?,?)",
                ORDER_ITEMS, (ps, i) -> {
            ps.setLong(1, i);
            ps.setLong(2, ((i - 1) * 7L) % ORDERS + 1);
            ps.setString(3, "P" + pad(i % 50, 3));
            ps.setInt(4, i % 9 + 1);
            ps.setBigDecimal(5, BigDecimal.valueOf((i * 31L) % 10_000 + 100, 2));
        });
        insert(c, "INSERT INTO composite_key_items (tenant_id, item_no, label, amount) VALUES (?,?,?,?)",
                COMPOSITE_KEY_ITEMS, (ps, i) -> {
            int tenant = (i - 1) / COMPOSITE_ITEMS_PER_TENANT + 1;
            int itemNo = (i - 1) % COMPOSITE_ITEMS_PER_TENANT + 1;
            ps.setInt(1, tenant);
            ps.setInt(2, itemNo);
            ps.setString(3, "label-" + pad(i % 25, 2));
            ps.setBigDecimal(4, BigDecimal.valueOf((i * 53L) % 5_000, 2));
        });
        // Nullable sort columns hold duplicates (i % 50) and NULLs on independent cycles (5, 7, 11).
        insert(c, "INSERT INTO nullable_sort_rows (id, sort_int, sort_text, sort_ts) VALUES (?,?,?,?)",
                NULLABLE_SORT_ROWS, (ps, i) -> {
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
        // A delivery's four parties, the approver absent on every fourth row (AC-FCH-14).
        insert(c, "INSERT INTO deliveries (id, sender_party_type, sender_party_id, recipient_party_type, "
                + "recipient_party_id, courier_party_type, courier_party_id, approver_party_type, approver_party_id) "
                + "VALUES (?,?,?,?,?,?,?,?,?)", DELIVERIES, (ps, i) -> {
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
        insert(c, "INSERT INTO card_holders (id, name) VALUES (?,?)", CARD_HOLDERS, (ps, i) -> {
            ps.setLong(1, i);
            ps.setString(2, "Holder " + pad(i, 2));
        });
        insert(c, "INSERT INTO cards (id, holder_id, issuer_id) VALUES (?,?,?)", CARDS, (ps, i) -> {
            ps.setLong(1, i);
            if (i <= 6) {
                ps.setLong(2, i + 4L);
            } else {
                ps.setNull(2, Types.BIGINT);
            }
            if (i % 4 == 0) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, i % 4 == 1 ? 900_000L + i : i);
            }
        });
        // java.util.Date attributes over DATE, TIME and TIMESTAMP columns (AC-COL-28).
        insert(c, "INSERT INTO dated_rows (id, born_on, rings_at, logged_at) VALUES (?,?,?,?)", DATED_ROWS, (ps, i) -> {
            ps.setLong(1, i);
            ps.setDate(2, java.sql.Date.valueOf(BASE.toLocalDate().plusDays((i == 8 ? 7 : i) - 1)));
            ps.setTime(3, java.sql.Time.valueOf(BASE.toLocalTime().plusHours(8).plusMinutes(i)));
            ps.setTimestamp(4, Timestamp.valueOf(BASE.plusHours(i).plusNanos(i % 3 == 0 ? 123_000 : 0)));
        });
        // One row per column-typed constant's mapping (TCK AC-FLT-20, AC-COL-32), plus a jsonb tags column on
        // PostgreSQL for a bound argument (TCK AC-COL-31).
        // PostgreSQL will not implicitly cast a text parameter to jsonb, so the tags parameter casts explicitly there.
        String constantRows = vendor == TckVendor.POSTGRESQL
                ? "INSERT INTO constant_rows (id, status, rank_no, active, label, tags, state)"
                        + " VALUES (?,?,?,?,?,CAST(? AS jsonb),?)"
                : "INSERT INTO constant_rows (id, status, rank_no, active, label, tags, state) VALUES (?,?,?,?,?,?,?)";
        insert(c, constantRows, CONSTANT_ROWS, (ps, i) -> {
            int row = i - 1;
            ps.setLong(1, i);
            nullableText(ps, 2, CONSTANT_STATUSES.get(row));
            if (CONSTANT_RANKS.get(row) == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setInt(3, CONSTANT_RANKS.get(row));
            }
            nullableText(ps, 4, CONSTANT_ACTIVE.get(row));
            nullableText(ps, 5, CONSTANT_LABELS.get(row));
            nullableText(ps, 6, CONSTANT_TAGS.get(row));
            String status = CONSTANT_STATUSES.get(row);
            nullableText(ps, 7, status == null ? null : status.substring(0, 1));
        });
        seedFolders(c);
        try (Statement statement = c.createStatement()) {
            statement.executeUpdate("INSERT INTO folder_nodes (id, parent_id, name) VALUES "
                    + "(200, NULL, 'diamond-root'), (201, NULL, 'left'), (202, NULL, 'right'), (203, NULL, 'leaf')");
            statement.executeUpdate("INSERT INTO folder_links (parent_id, child_id) VALUES "
                    + "(200, 201), (200, 202), (201, 203), (202, 203)");
        }
        seedCompositeNodes(c);
    }

    /** A {@code folder_nodes} row: its id and its parent's, {@code null} at a root. */
    private record Folder(long id, Long parent) {}

    /** The folders: a tree, a chain, a two-row cycle and a 999-row chain (TCK AC-SRC-12 to AC-SRC-14). */
    private static List<Folder> folders() {
        List<Folder> nodes = new ArrayList<>(List.of(new Folder(FOLDER_TREE_ROOT, null), new Folder(2, 1L),
                new Folder(3, 1L), new Folder(4, 2L), new Folder(5, 2L), new Folder(6, 3L)));
        for (long id = FOLDER_CHAIN_ROOT; id < FOLDER_CHAIN_ROOT + FOLDER_CHAIN_NODES; id++) {
            nodes.add(new Folder(id, id == FOLDER_CHAIN_ROOT ? null : id - 1));
        }
        // Two rows that point at each other, so the tree the seed walks is cyclic (AC-SRC-13).
        nodes.add(new Folder(FOLDER_CYCLE_ROOT, FOLDER_CYCLE_ROOT + 1));
        nodes.add(new Folder(FOLDER_CYCLE_ROOT + 1, FOLDER_CYCLE_ROOT));
        for (long id = FOLDER_DEEP_ROOT; id < FOLDER_DEEP_ROOT + FOLDER_DEEP_NODES; id++) {
            nodes.add(new Folder(id, id == FOLDER_DEEP_ROOT ? null : id - 1));
        }
        return nodes;
    }

    private static void seedFolders(Connection c) throws SQLException {
        List<Folder> nodes = folders();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO folder_nodes (id, parent_id, name) VALUES (?,?,?)")) {
            for (int i = 0; i < nodes.size(); i++) {
                Folder node = nodes.get(i);
                ps.setLong(1, node.id());
                if (node.parent() == null) {
                    ps.setNull(2, Types.BIGINT);
                } else {
                    ps.setLong(2, node.parent());
                }
                ps.setString(3, "node-" + node.id());
                ps.addBatch();
                if ((i + 1) % BATCH == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
    }

    /** A {@code composite_nodes} row: its parent's id, {@code null} at the root (AC-SRC-21). */
    private record CompositeNode(long id, Long parent) {}

    /** The one tree {@code composite_nodes} holds, in one tenant, shaped as {@code folder_nodes}' tree is. */
    private static List<CompositeNode> compositeNodes() {
        return List.of(new CompositeNode(COMPOSITE_TREE_ROOT, null), new CompositeNode(2, 1L),
                new CompositeNode(3, 1L), new CompositeNode(4, 2L), new CompositeNode(5, 2L),
                new CompositeNode(6, 3L));
    }

    private static void seedCompositeNodes(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO composite_nodes (tenant_id, id, parent_id, name) VALUES (?,?,?,?)")) {
            for (CompositeNode node : compositeNodes()) {
                ps.setInt(1, COMPOSITE_TREE_TENANT);
                ps.setLong(2, node.id());
                if (node.parent() == null) {
                    ps.setNull(3, Types.BIGINT);
                } else {
                    ps.setLong(3, node.parent());
                }
                ps.setString(4, "node-" + node.id());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Sets {@code index} to {@code value}, or NULL when it is {@code null}. */
    private static void nullableText(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    /** The code of {@code string_key_products} row {@code row} (1-based), a permutation with mixed case. */
    private static String stringProductCode(int row) {
        return (row % 5 == 0 ? "p-" : "P-") + pad((row * 7) % STRING_KEY_PRODUCTS + 1, 4);
    }

    /** The tree, the chain, the cycle and the deep chain {@code folder_nodes} holds (TCK AC-SRC-12 to AC-SRC-14). */
    public static final int FOLDER_TREE_NODES = 6;

    public static final int FOLDER_CHAIN_NODES = 6;

    public static final int FOLDER_CYCLE_NODES = 2;

    /** The chain whose 999 rows a MySQL {@code maxDepth} of 998 walks, one level per row (AC-SRC-14). */
    public static final int FOLDER_DEEP_NODES = 999;

    public static final int FOLDER_NODES =
            FOLDER_TREE_NODES + FOLDER_CHAIN_NODES + FOLDER_CYCLE_NODES + FOLDER_DEEP_NODES + 4;

    /** Root ids of the tree, the chain, the cycle and the deep chain, each a step's seed row. */
    public static final long FOLDER_TREE_ROOT = 1;

    public static final long FOLDER_CHAIN_ROOT = 10;

    public static final long FOLDER_CYCLE_ROOT = 100;

    public static final long FOLDER_DEEP_ROOT = 1_000;

    /** The tree {@code composite_nodes} holds: one tenant, a root and five rows below it (AC-SRC-21). */
    public static final int COMPOSITE_TREE_TENANT = 1;

    public static final int COMPOSITE_TREE_NODES = 6;

    public static final long COMPOSITE_TREE_ROOT = 1;

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
