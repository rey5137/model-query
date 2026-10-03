package com.rey.modelquery.tck.fch;

import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import jakarta.persistence.Query;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** What the fetch-plan TCK cases share: executors, the expected children read by JDBC, and a narrowed profile. */
final class FetchTestSupport {

    private FetchTestSupport() {}

    /** Runs {@code work} on an executor of {@code root}, over {@code ds}, with the default configuration. */
    static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        withExecutor(ds, root, ModelQueryConfig.defaults(), work);
    }

    /** Runs {@code work} on an executor of {@code root} over {@code ds} with {@code config}, outside a transaction. */
    static <E> void withExecutor(DataSource ds, Class<E> root, ModelQueryConfig config,
            Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, config)));
        }
    }

    /**
     * The second column of {@code sql}'s rows grouped by the first, both {@code BIGINT}, each group in the order the
     * rows come: what a child load should set on each parent key.
     */
    static Map<Long, List<Long>> grouped(TckDatabase db, String sql) {
        var result = new LinkedHashMap<Long, List<Long>>();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.computeIfAbsent(rows.getLong(1), key -> new ArrayList<>()).add(rows.getLong(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
        return result;
    }

    /** {@code db}'s built-in profile with an IN list of at most {@code maxInListSize} values (R-VND-03). */
    static VendorProfile limited(TckDatabase db, int maxInListSize) {
        return limited(db, maxInListSize, 0);
    }

    /** As {@link #limited(TckDatabase, int)}, with at most {@code maxBindParameters} binds a statement when above 0. */
    static VendorProfile limited(TckDatabase db, int maxInListSize, int maxBindParameters) {
        VendorProfile builtIn;
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            builtIn = VendorResolver.resolve(sf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile();
        }
        return new VendorProfile() {
            @Override
            public DatabaseVendor vendor() {
                return builtIn.vendor();
            }

            @Override
            public int maxInListSize() {
                return maxInListSize;
            }

            @Override
            public int maxBindParameters() {
                return maxBindParameters > 0 ? maxBindParameters : builtIn.maxBindParameters();
            }

            @Override
            public int streamingFetchSize(int requested) {
                return builtIn.streamingFetchSize(requested);
            }

            @Override
            public void applyTimeout(Query query, Duration timeout) {
                builtIn.applyTimeout(query, timeout);
            }

            @Override
            public NullOrdering defaultAscendingNullOrdering() {
                return builtIn.defaultAscendingNullOrdering();
            }
        };
    }

    /** The number of bind markers in {@code sql}. */
    static long binds(String sql) {
        return sql.chars().filter(c -> c == '?').count();
    }
}
