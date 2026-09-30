package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.Query;
import jakarta.persistence.QueryTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;

/**
 * Every Tier-1 value of vendor/41 §2, asserted against the running database rather than the profile constant
 * (R-PRF-11). The vendor-specific SQL for the sleeping query is why this package may name a vendor (R-VND-04).
 */
class ProfileValuesTest {

    private static final String HQL_ALL = "select e.sortInt from NullableSortEntity e";

    @TckTest
    void ac_prf_06_the_default_ascending_null_ordering_matches_the_ordering_the_database_returns(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            VendorProfile profile = profile(sf);
            List<Integer> asc = sf.fromSession(em -> em.createNativeQuery(
                    "select sort_int from nullable_sort_rows order by sort_int asc", Integer.class).getResultList());
            assertThat(asc).contains((Integer) null).anyMatch(v -> v != null);
            NullOrdering observed = asc.get(0) == null ? NullOrdering.NULLS_FIRST : NullOrdering.NULLS_LAST;
            assertThat(asc.get(asc.size() - 1) == null).isEqualTo(observed == NullOrdering.NULLS_LAST);
            assertThat(profile.defaultAscendingNullOrdering()).isEqualTo(observed);
        }
    }

    @TckTest
    void ac_prf_01_the_in_list_limit_is_accepted_by_the_database(TckDatabase db) throws SQLException {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            int max = profile(sf).maxInListSize();
            assertThat(max).isEqualTo(10_000);
            assertThat(countWhereIdIn(db, max)).isZero();
        }
    }

    @TckTest
    void ac_prf_01_the_bind_parameter_limit_is_accepted_by_the_database(TckDatabase db) throws SQLException {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            int max = profile(sf).maxBindParameters();
            assertThat(countWhereIdIn(db, max)).isZero();
        }
    }

    @TckTest
    void ac_prf_01_the_query_timeout_hint_cancels_a_query_that_cannot_finish(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            VendorProfile profile = profile(sf);
            long start = System.nanoTime();
            assertThatThrownBy(() -> sf.inSession(em -> {
                Query query = em.createNativeQuery(sleepingSql(db));
                profile.applyTimeout(query, Duration.ofMillis(400));
                query.getResultList();
            })).isInstanceOf(QueryTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(500),
                    Duration.ofSeconds(8));
        }
    }

    @TckTest
    void ac_prf_01_streaming_inside_a_transaction_reads_every_row(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            VendorProfile profile = profile(sf);
            long total = sf.fromSession(em -> ((Number) em.createQuery(
                    "select count(e) from NullableSortEntity e").getSingleResult()).longValue());
            assertThat(total).isGreaterThan(1);
            List<Integer> streamed = sf.fromTransaction(em -> {
                Query query = em.createQuery(HQL_ALL, Integer.class);
                profile.applyStreaming(query, 2);
                return query.getResultList();
            });
            assertThat(streamed).hasSize((int) total);
        }
    }

    @TckTest
    void ac_prf_01_explicit_nulls_first_and_last_give_the_requested_order(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            for (String direction : List.of("asc", "desc")) {
                List<Integer> first = ordered(sf, direction + " nulls first");
                assertThat(first.get(0)).as("%s nulls first", direction).isNull();
                assertThat(first.get(first.size() - 1)).isNotNull();
                List<Integer> last = ordered(sf, direction + " nulls last");
                assertThat(last.get(0)).as("%s nulls last", direction).isNotNull();
                assertThat(last.get(last.size() - 1)).isNull();
            }
        }
    }

    @TckTest
    void ac_prf_01_a_row_value_keyset_predicate_executes(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            List<?> rows = sf.fromSession(em -> em.createNativeQuery(
                    "select id from nullable_sort_rows where (sort_int, id) > (?1, ?2) order by sort_int, id")
                    .setParameter(1, 0).setParameter(2, 0L).getResultList());
            List<?> viaOr = sf.fromSession(em -> em.createNativeQuery(
                    "select id from nullable_sort_rows where sort_int > ?1 or (sort_int = ?1 and id > ?2) "
                            + "order by sort_int, id").setParameter(1, 0).setParameter(2, 0L).getResultList());
            assertThat(rows).isNotEmpty().isEqualTo(viaOr);
        }
    }

    private static List<Integer> ordered(SessionFactory sf, String order) {
        return sf.fromSession(em -> em.createQuery(HQL_ALL + " order by e.sortInt " + order + ", e.id", Integer.class)
                .getResultList());
    }

    private static VendorProfile profile(SessionFactory sf) {
        return VendorResolver.resolve(sf, Optional.<DatabaseVendor>empty()).profile();
    }

    /** {@code select count(*) ... where id in (?, ... n binds)} over plain JDBC: one statement, n binds. */
    private static long countWhereIdIn(TckDatabase db, int binds) throws SQLException {
        String sql = "select count(*) from nullable_sort_rows where id in ("
                + IntStream.range(0, binds).mapToObj(i -> "?").collect(Collectors.joining(",")) + ")";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 1; i <= binds; i++) {
                ps.setLong(i, -i);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String sleepingSql(TckDatabase db) {
        return switch (db.vendor()) {
            case H2 -> "select count(*) from system_range(1, 1000000000) a, system_range(1, 1000000000) b";
            case POSTGRESQL -> "select pg_sleep(30)";
            case MYSQL -> "select sleep(30)";
        };
    }
}
