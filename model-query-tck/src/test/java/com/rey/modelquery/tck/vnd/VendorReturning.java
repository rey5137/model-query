package com.rey.modelquery.tck.vnd;

import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.WriteStatement;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import java.util.Optional;
import org.hibernate.SessionFactory;

/** What a database's built-in profile says of returning the rows a write changed (vendor/40 R-VND-17). */
public final class VendorReturning {

    private VendorReturning() {}

    /** Whether the profile declares a shape for an update, so {@code updateReturning} returns rows (R-WRT-50). */
    public static boolean returnsRows(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            return VendorResolver.resolve(sf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile()
                    .returning(WriteStatement.UPDATE).isPresent();
        }
    }
}
