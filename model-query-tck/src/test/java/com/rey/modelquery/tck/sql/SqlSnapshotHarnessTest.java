package com.rey.modelquery.tck.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the SQL-snapshot harness (R-QA-03): a plain JDBC query is captured through the proxy and matched against a
 * per-vendor snapshot. It does not exercise the library's SQL generation; that starts with M1's query builder.
 */
class SqlSnapshotHarnessTest {

    // No acceptance criterion covers the harness itself, so this is named plainly (R-QA-05 applies to criteria).
    @TckTest
    void reference_query_matches_its_snapshot(TckDatabase db) {
        SqlSnapshots.assertMatches(db, "reference-jdbc-query", dataSource -> {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps = c.prepareStatement(
                            "SELECT id, name\n  FROM customers\n WHERE country = ? AND vip = ?\n ORDER BY id")) {
                ps.setString(1, "US");
                ps.setBoolean(2, true);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                }
            }
        });
    }

    @Test
    void normalize_collapses_whitespace_and_keeps_placeholders() {
        assertThat(SqlSnapshots.normalize("  SELECT a,\n\tb  FROM t\n WHERE x = ? "))
                .isEqualTo("SELECT a, b FROM t WHERE x = ?");
    }

    @Test
    void diff_marks_removed_and_added_lines() {
        assertThat(SqlSnapshots.diff(List.of("a", "b", "c"), List.of("a", "x", "c")))
                .isEqualTo("  a\n- b\n+ x\n  c\n");
    }
}
