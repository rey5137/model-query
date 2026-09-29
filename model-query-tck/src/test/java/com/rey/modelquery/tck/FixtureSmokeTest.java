package com.rey.modelquery.tck;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** Harness smoke test: the fixture schema exists and the seed is complete on every selected vendor. */
class FixtureSmokeTest {

    // No acceptance criterion covers the harness itself, so this is named plainly (R-QA-05 applies to criteria).
    @TckTest
    void fixture_schema_is_present_with_the_expected_row_counts(TckDatabase db) throws SQLException {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("customers", TckFixture.CUSTOMERS);
        expected.put("orders", TckFixture.ORDERS);
        expected.put("order_items", TckFixture.ORDER_ITEMS);
        expected.put("composite_key_items", TckFixture.COMPOSITE_KEY_ITEMS);
        expected.put("nullable_sort_rows", TckFixture.NULLABLE_SORT_ROWS);

        Map<String, Integer> actual = new LinkedHashMap<>();
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            for (String table : expected.keySet()) {
                actual.put(table, count(s, "SELECT COUNT(*) FROM " + table));
            }
            assertThat(count(s, "SELECT COUNT(*) FROM nullable_sort_rows WHERE sort_int IS NULL")).isEqualTo(600);
            assertThat(count(s, "SELECT COUNT(*) FROM nullable_sort_rows WHERE sort_text IS NULL")).isEqualTo(428);
            assertThat(count(s, "SELECT COUNT(*) FROM nullable_sort_rows WHERE sort_ts IS NULL")).isEqualTo(272);
            // Identical across vendors: a checksum of the seeded numbers.
            assertThat(count(s, "SELECT SUM(quantity) FROM order_items")).isEqualTo(99_995);
        }
        assertThat(actual).isEqualTo(expected);
    }

    private static int count(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
