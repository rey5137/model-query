package com.rey.modelquery.tck.harness;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Assumptions;

/** A started, schema-created and seeded database. Connections are plain JDBC; M1 layers JPA on top. */
public final class TckDatabase {

    private final TckTarget target;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    TckDatabase(TckTarget target, String jdbcUrl, String username, String password) {
        this.target = target;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public TckTarget target() {
        return target;
    }

    public TckVendor vendor() {
        return target.vendor();
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    /** This database with a JDBC URL property added, such as MySQL's {@code useCursorFetch=true}. */
    public TckDatabase withJdbcUrlProperty(String name, String value) {
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return new TckDatabase(target, jdbcUrl + separator + name + "=" + value, username, password);
    }

    /**
     * Skips the calling test where the database cannot read a write's target table in a sub-query (MySQL, error
     * 1093). The engine writes key-first there (spec api/14 R-WRT-11), which lands in M6.4; remove this then.
     */
    public void assumeTargetTableInSubquery() {
        Assumptions.assumeTrue(target.vendor() != TckVendor.MYSQL,
                "a write reading its target table in a sub-query runs key-first on " + target + " from M6.4");
    }

    /** A new connection; the caller closes it. */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    @Override
    public String toString() {
        return target.toString();
    }
}
