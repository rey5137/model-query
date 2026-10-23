package com.rey.modelquery.spike.rfc0019;

import com.rey.modelquery.spike.rfc0019.LitePosEntities.SaleStatus;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.Random;
import java.util.TimeZone;

public final class LitePosDataset {

    public static final Instant BASE_TIME = Instant.parse("2025-01-01T00:00:00Z");
    public static final Instant TODAY_START = BASE_TIME.plus(364, ChronoUnit.DAYS);
    public static final Instant TODAY_END = BASE_TIME.plus(365, ChronoUnit.DAYS);

    private LitePosDataset() {}

    public static void seed(Connection conn, boolean full) throws SQLException {
        conn.setAutoCommit(false);
        seedStores(conn);
        seedTerminals(conn);
        seedCashiers(conn);
        seedSales(conn, full);
        conn.commit();
    }

    private static void seedStores(Connection conn) throws SQLException {
        String sql = "INSERT INTO store (id, code, name) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (long id = 1; id <= 5; id++) {
                ps.setLong(1, id);
                ps.setString(2, String.format("S%03d", id));
                ps.setString(3, "Store " + id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void seedTerminals(Connection conn) throws SQLException {
        String sql = "INSERT INTO terminal (id, store_id, serial_number) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (long id = 1; id <= 10; id++) {
                long storeId = ((id - 1) % 5) + 1;
                ps.setLong(1, id);
                ps.setLong(2, storeId);
                ps.setString(3, String.format("TERM-%04d", id));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void seedCashiers(Connection conn) throws SQLException {
        String sql = "INSERT INTO cashier (id, store_id, code, name) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (long id = 1; id <= 20; id++) {
                long storeId = ((id - 1) % 5) + 1;
                ps.setLong(1, id);
                ps.setLong(2, storeId);
                ps.setString(3, String.format("CASH-%03d", id));
                ps.setString(4, "Cashier " + id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void seedSales(Connection conn, boolean full) throws SQLException {
        String saleSql = "INSERT INTO sale (id, receipt_number, store_id, terminal_id, cashier_id, sale_time, "
                + "status, total_amount, tax_amount, discount_amount, note, item_count) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String lineSql = "INSERT INTO sale_line (id, sale_id, line_number, product_sku, quantity, unit_price, "
                + "line_total) VALUES (?, ?, ?, ?, ?, ?, ?)";

        int days = full ? 365 : 7;
        int salesPerDay = full ? 236 : 100;
        Random rng = new Random(42L);

        try (PreparedStatement psSale = conn.prepareStatement(saleSql);
             PreparedStatement psLine = conn.prepareStatement(lineSql)) {

            long saleId = 1;
            long lineId = 1;
            int batchCount = 0;

            for (int day = 0; day < days; day++) {
                Instant dayStart = full
                        ? BASE_TIME.plus(day, ChronoUnit.DAYS)
                        : (day == days - 1 ? TODAY_START : TODAY_START.minus(days - 1 - day, ChronoUnit.DAYS));

                int daySales = salesPerDay;
                // On "today", ensure store 1 has exactly 50 COMPLETED sales
                if (day == days - 1) {
                    daySales = Math.max(daySales, 200);
                }

                int store1CompletedToday = 0;

                for (int s = 0; s < daySales; s++) {
                    long currentSaleId = saleId++;
                    long storeId;
                    SaleStatus status;

                    if (day == days - 1) {
                        if (store1CompletedToday < 50) {
                            storeId = 1L;
                            status = SaleStatus.COMPLETED;
                            store1CompletedToday++;
                        } else {
                            storeId = (rng.nextInt(4)) + 2;
                            int st = rng.nextInt(10);
                            status = (st < 8) ? SaleStatus.COMPLETED
                                    : (st == 8 ? SaleStatus.CANCELLED : SaleStatus.REFUNDED);
                        }
                    } else {
                        storeId = (rng.nextInt(5)) + 1;
                        int st = rng.nextInt(10);
                        status = (st < 8) ? SaleStatus.COMPLETED
                                : (st == 8 ? SaleStatus.CANCELLED : SaleStatus.REFUNDED);
                    }

                    long terminalId = (storeId - 1) * 2 + 1 + rng.nextInt(2);
                    long cashierId = (storeId - 1) * 4 + 1 + rng.nextInt(4);
                    int secondOffset = rng.nextInt(86400);
                    Instant saleTime = dayStart.plusSeconds(secondOffset);

                    int linesCount = 3 + rng.nextInt(4); // 3 to 6 lines
                    BigDecimal totalAmount = BigDecimal.ZERO;
                    BigDecimal taxAmount = BigDecimal.valueOf(linesCount * 50L, 2);
                    BigDecimal discountAmount = BigDecimal.valueOf(linesCount * 20L, 2);

                    for (int ln = 1; ln <= linesCount; ln++) {
                        BigDecimal qty = BigDecimal.valueOf(1 + rng.nextInt(3));
                        BigDecimal price = BigDecimal.valueOf((5 + rng.nextInt(20)) * 100L, 2);
                        BigDecimal lineTotal = qty.multiply(price);
                        totalAmount = totalAmount.add(lineTotal);

                        psLine.setLong(1, lineId++);
                        psLine.setLong(2, currentSaleId);
                        psLine.setInt(3, ln);
                        psLine.setString(4, "SKU-" + (100 + rng.nextInt(50)));
                        psLine.setBigDecimal(5, qty);
                        psLine.setBigDecimal(6, price);
                        psLine.setBigDecimal(7, lineTotal);
                        psLine.addBatch();
                    }

                    psSale.setLong(1, currentSaleId);
                    psSale.setString(2, String.format("REC-%07d", currentSaleId));
                    psSale.setLong(3, storeId);
                    psSale.setLong(4, terminalId);
                    psSale.setLong(5, cashierId);
                    psSale.setTimestamp(6, Timestamp.from(saleTime));
                    psSale.setString(7, status.name());
                    psSale.setBigDecimal(8, totalAmount);
                    psSale.setBigDecimal(9, taxAmount);
                    psSale.setBigDecimal(10, discountAmount);
                    psSale.setString(11, "Note for sale " + currentSaleId);
                    psSale.setLong(12, (long) linesCount);
                    psSale.addBatch();

                    batchCount++;
                    if (batchCount % 2000 == 0) {
                        psSale.executeBatch();
                        psLine.executeBatch();
                    }
                }
            }

            if (batchCount % 2000 != 0) {
                psSale.executeBatch();
                psLine.executeBatch();
            }
        }
    }
}
