package com.rey.modelquery.spike.rfc0022;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

/** Deterministic: exactly 50,000 invoices, 1,000 orphan rep codes and 300 rows of each employee variant. */
final class Rfc0022Dataset {
    static final int INVOICES = 50_000;
    static final int EMPLOYEES = 300;
    private Rfc0022Dataset() {}

    static String code(int id) { return "E%03d".formatted(id); }
    static String region(int id) { return "R%02d".formatted(1 + (id - 1) % 10); }

    static void seed(Connection connection) throws SQLException {
        Rfc0022ClosureDataset.seed(connection);
        seedInvoices(connection);
    }

    static void seedInvoices(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            batch(connection, "insert into team (id, name) values (?, ?)", 10, (ps, id) -> {
                ps.setLong(1, id);
                ps.setString(2, "Team " + id);
            });
            batch(connection, "insert into region (code, name, rep_code) values (?, ?, ?)", 10, (ps, id) -> {
                ps.setString(1, region(id));
                ps.setString(2, "Region " + id);
                ps.setString(3, code(id));
            });
            batch(connection, "insert into employee (code, name, active, team_id) values (?, ?, ?, ?)",
                    EMPLOYEES, (ps, id) -> {
                        ps.setString(1, code(id));
                        ps.setString(2, "Employee " + id);
                        ps.setBoolean(3, id % 5 != 0);
                        ps.setLong(4, 1 + (id - 1) % 10);
                    });
            for (String table : new String[] {"employee_ic", "employee_emb"}) {
                batch(connection, "insert into " + table
                                + " (region_code, code, name, active, team_id) values (?, ?, ?, ?, ?)",
                        EMPLOYEES, (ps, id) -> {
                            ps.setString(1, region(id));
                            ps.setString(2, code(id));
                            ps.setString(3, "Employee " + id);
                            ps.setBoolean(4, id % 5 != 0);
                            ps.setLong(5, 1 + (id - 1) % 10);
                        });
            }
            batch(connection, "insert into invoice (id, invoice_number, rep_code, region_code, issued_on) "
                    + "values (?, ?, ?, ?, ?)", INVOICES, (ps, id) -> {
                        int rep = 1 + (id - 1) % EMPLOYEES;
                        ps.setLong(1, id);
                        ps.setString(2, "INV-" + id);
                        ps.setString(3, id % 50 == 0 ? "ORPHAN" : code(rep));
                        ps.setString(4, id % 25 == 0 ? null : region(rep));
                        ps.setDate(5, Date.valueOf(LocalDate.of(2025, 1, 1).plusDays(id % 365)));
                    });
            batch(connection, "insert into ticket (id, title) values (?, ?)", 100, (ps, id) -> {
                ps.setLong(1, id);
                ps.setString(2, "Ticket " + id);
            });
            batch(connection, "insert into attachment (id, owner_type, owner_id) values (?, ?, ?)", 202, (ps, id) -> {
                ps.setLong(1, id);
                ps.setString(2, id % 2 == 1 ? "INVOICE" : "TICKET");
                ps.setLong(3, id <= 200 ? (id + 1) / 2 : INVOICES + 1);
            });
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    interface Bind { void row(PreparedStatement statement, int id) throws SQLException; }

    private static void batch(Connection connection, String sql, int size, Bind bind) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(60);
            for (int id = 1; id <= size; id++) {
                bind.row(statement, id);
                statement.addBatch();
                if (id % 1_000 == 0 || id == size) {
                    statement.executeBatch();
                }
            }
        }
    }
}
