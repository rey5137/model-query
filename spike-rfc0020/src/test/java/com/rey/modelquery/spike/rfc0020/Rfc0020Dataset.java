package com.rey.modelquery.spike.rfc0020;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Random;

/**
 * LitePOS's shape (RFC 0020 §Spike plan): 365 business days, sales on the last 30 of them at 1,000 sales and 3,000
 * lines a day, a year of inventory movements (200 a day), deterministic (seeded {@link Random}). The sale's
 * business-date column has no index here; the plan cells add and drop {@value #SALE_DATE_INDEX}.
 */
public final class Rfc0020Dataset {

    public static final LocalDate FIRST_DAY = LocalDate.of(2025, 1, 1);
    public static final int DAYS = 365;
    public static final int SALES_DAYS = 30;
    public static final int SALES_PER_DAY = 1_000;
    public static final int LINES_PER_SALE = 3;
    public static final int ITEMS = 200;
    public static final int CUSTOMERS = 500;
    public static final int PROMOS = 80;
    public static final int MOVEMENTS_PER_DAY = 200;
    public static final int SNAPSHOT_DAYS = 30;
    public static final String SALE_DATE_INDEX = "ix_sale_business_date";

    private Rfc0020Dataset() {}

    public static LocalDate day(int id) {
        return FIRST_DAY.plusDays(id - 1L);
    }

    /** The first business day that has sales. */
    public static int firstSalesDay() {
        return DAYS - SALES_DAYS + 1;
    }

    public static void seed(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        Random random = new Random(20);
        businessDays(conn, random);
        items(conn, random);
        customers(conn, random);
        promos(conn);
        sales(conn, random);
        movements(conn, random);
        snapshots(conn, random);
        exactTables(conn);
        conn.commit();
        conn.setAutoCommit(true);
    }

    private static void businessDays(Connection conn, Random random) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into business_day (id, business_date, counted_cash) values (?, ?, ?)")) {
            for (int id = 1; id <= DAYS; id++) {
                ps.setLong(1, id);
                ps.setDate(2, Date.valueOf(day(id)));
                ps.setBigDecimal(3, money(random, 5_000_000));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void items(Connection conn, Random random) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into item (id, name, category, price) values (?, ?, ?, ?)")) {
            for (int id = 1; id <= ITEMS; id++) {
                ps.setLong(1, id);
                ps.setString(2, String.format("Item %03d", id));
                ps.setString(3, "C" + (id % 10));
                ps.setBigDecimal(4, money(random, 10_000));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void customers(Connection conn, Random random) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into customer (id, name, vip, region) values (?, ?, ?, ?)")) {
            for (int id = 1; id <= CUSTOMERS; id++) {
                ps.setLong(1, id);
                ps.setString(2, "Customer " + id);
                ps.setBoolean(3, id % 4 == 0);
                setNullable(ps, 4, random.nextInt(3) == 0 ? null : "R" + (id % 5));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void promos(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("insert into promo (id, code, region) values (?, ?, ?)")) {
            for (int id = 1; id <= PROMOS; id++) {
                ps.setLong(1, id);
                // Codes P1..P50, ten NULL codes (a NULL sale code matches a NULL promo code only null-safely), and
                // X1..X20, which no sale has (sales draw P1..P55): an exists over them must find no row.
                setNullable(ps, 2, id <= 50 ? "P" + id : id <= 60 ? null : "X" + (id - 60));
                setNullable(ps, 3, id % 3 == 0 ? null : "R" + (id % 5));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void sales(Connection conn, Random random) throws SQLException {
        try (PreparedStatement sale = conn.prepareStatement("insert into sale (id, business_date, kind, total, "
                + "cash_total, customer_id, promo_code, item_count, line_count) values (?, ?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement line = conn.prepareStatement(
                     "insert into sale_line (id, sale_id, item_id, line_no, qty, amount) values (?, ?, ?, ?, ?, ?)")) {
            long saleId = 0;
            long lineId = 0;
            for (int dayId = firstSalesDay(); dayId <= DAYS; dayId++) {
                for (int n = 0; n < SALES_PER_DAY; n++) {
                    saleId++;
                    BigDecimal total = BigDecimal.ZERO;
                    int items = 0;
                    for (int k = 1; k <= LINES_PER_SALE; k++) {
                        lineId++;
                        int qty = 1 + random.nextInt(5);
                        BigDecimal amount = money(random, 20_000);
                        total = total.add(amount);
                        items += qty;
                        line.setLong(1, lineId);
                        line.setLong(2, saleId);
                        line.setLong(3, 1 + random.nextInt(ITEMS));
                        line.setShort(4, (short) k);
                        line.setInt(5, qty);
                        line.setBigDecimal(6, amount);
                        line.addBatch();
                    }
                    sale.setLong(1, saleId);
                    sale.setDate(2, Date.valueOf(day(dayId)));
                    sale.setString(3, random.nextInt(20) == 0 ? "REFUND" : "SALE");
                    sale.setBigDecimal(4, total);
                    sale.setBigDecimal(5, random.nextBoolean() ? total : BigDecimal.ZERO.setScale(2));
                    if (random.nextInt(5) < 2) {
                        sale.setNull(6, Types.BIGINT);
                    } else {
                        sale.setLong(6, 1 + random.nextInt(CUSTOMERS));
                    }
                    setNullable(sale, 7, random.nextBoolean() ? null : "P" + (1 + random.nextInt(55)));
                    sale.setInt(8, items);
                    sale.setLong(9, LINES_PER_SALE);
                    sale.addBatch();
                }
                sale.executeBatch();
                line.executeBatch();
            }
        }
    }

    private static void movements(Connection conn, Random random) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into inventory_movement (id, item_id, business_date, qty) values (?, ?, ?, ?)")) {
            long id = 0;
            for (int dayId = 1; dayId <= DAYS; dayId++) {
                for (int n = 0; n < MOVEMENTS_PER_DAY; n++) {
                    ps.setLong(1, ++id);
                    ps.setLong(2, 1 + random.nextInt(ITEMS));
                    ps.setDate(3, Date.valueOf(day(dayId)));
                    ps.setInt(4, random.nextInt(41) - 20);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private static void snapshots(Connection conn, Random random) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "insert into stock_snapshot (item_id, business_date, qty) values (?, ?, ?)")) {
            for (int dayId = DAYS - SNAPSHOT_DAYS + 1; dayId <= DAYS; dayId++) {
                for (int item = 1; item <= ITEMS; item++) {
                    ps.setLong(1, item);
                    ps.setDate(2, Date.valueOf(day(dayId)));
                    ps.setInt(3, random.nextInt(100));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private static void exactTables(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("insert into null_pair (id, a, b) values (1, 'x', 'x'), (2, 'x', 'y'), (3, 'x', null), "
                    + "(4, null, 'x'), (5, null, null)");
            // Group 1 averages 5/3 in every column; group 2 is a single row; no row has group 99 (zero rows).
            st.executeUpdate("insert into money_row (id, grp, amount, big_val, int_val, small_val) values "
                    + "(1, 1, 1.00, 1, 1, 1), (2, 1, 2.00, 2, 2, 2), (3, 1, 2.00, 2, 2, 2), "
                    + "(4, 2, 0.01, 7, 7, 7)");
            // B.1: group 1 ties on k (id 1 wins on id; id 1 fails ok), group 2 has a NULL k, group 3's first value is
            // NULL, group 4's tie was inserted with the higher id first.
            st.executeUpdate("insert into rank_row (id, grp, k, v, tag, ok) values "
                    + "(1, 1, 5, 'a', 'H', false), (2, 1, 5, 'b', 'L', true), (3, 1, 3, 'c', 'L', true), "
                    + "(4, 2, null, 'd', 'H', true), (5, 2, 1, 'e', 'L', true), "
                    + "(6, 3, 2, null, 'H', true), (7, 3, 1, 'g', 'L', true), "
                    + "(9, 4, 7, 'i', 'L', true), (8, 4, 7, 'h', 'H', true)");
            st.executeUpdate("insert into round_row (id, v) values (1, 2.345), (2, -2.345), (3, 2.500), (4, -2.500)");
        }
    }

    private static BigDecimal money(Random random, int maxCents) {
        return BigDecimal.valueOf(1 + random.nextInt(maxCents), 2);
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
