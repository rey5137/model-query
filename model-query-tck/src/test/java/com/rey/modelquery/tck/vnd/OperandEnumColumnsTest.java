package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.tck.flt.HibernateOperandBinderTest;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Assumptions;

/** MySQL's column kinds for the {@code STRING} enum mappings the operand binder test runs against (TCK AC-FLT-22). */
class OperandEnumColumnsTest {

    @TckTest
    void ac_flt_22_mysqls_string_enum_columns_are_a_native_enum_and_a_varchar(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.MYSQL, "only MySQL maps a STRING enum to a native enum");
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = HibernateOperandBinderTest.factory(ds, "bind"); Connection c = ds.getConnection();
                    PreparedStatement types = c.prepareStatement("SELECT column_name, data_type FROM"
                            + " information_schema.columns WHERE table_schema = database()"
                            + " AND table_name = 'operand_rows' AND column_name IN ('phase', 'phase_text')")) {
                Map<String, String> byColumn = new HashMap<>();
                try (ResultSet rs = types.executeQuery()) {
                    while (rs.next()) {
                        byColumn.put(rs.getString(1).toLowerCase(), rs.getString(2).toLowerCase());
                    }
                }
                // The binding test covers both: these are the two column kinds it ran against.
                assertThat(byColumn).containsEntry("phase", "enum").containsEntry("phase_text", "varchar");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
