package com.rey.modelquery.jpa.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.spi.VendorProfile;
import jakarta.persistence.Query;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import org.junit.jupiter.api.Test;

class VendorResolverTest {

    /** A discovered profile, as a user registers with {@code ServiceLoader}. */
    private record CustomProfile(DatabaseVendor vendor) implements VendorProfile {

        @Override
        public int maxInListSize() {
            return 500;
        }

        @Override
        public int maxBindParameters() {
            return 1_000;
        }

        @Override
        public void applyStreaming(Query query, int fetchSize) {}

        @Override
        public void applyTimeout(Query query, Duration timeout) {}

        @Override
        public NullOrdering defaultAscendingNullOrdering() {
            return NullOrdering.UNKNOWN;
        }
    }

    @Test
    void ac_vnd_05_an_unknown_product_name_yields_other_whose_null_ordering_is_unknown() {
        // UNKNOWN is what makes a nullable keyset column refuse its NULLs under OTHER (tck NullOrderingTest).
        VendorProfile other = profileFor(VendorResolver.vendorOf("Apache Derby"), Map.of());
        assertThat(other.vendor()).isEqualTo(DatabaseVendor.OTHER);
        assertThat(other.defaultAscendingNullOrdering()).isEqualTo(NullOrdering.UNKNOWN);
    }

    @Test
    void r_vnd_06_an_unknown_product_name_resolves_to_the_other_profile() {
        assertThat(VendorResolver.vendorOf("Apache Derby")).isEqualTo(DatabaseVendor.OTHER);
        assertThat(VendorResolver.vendorOf(null)).isEqualTo(DatabaseVendor.OTHER);
        assertThat(profileFor(DatabaseVendor.OTHER, Map.of())).isSameAs(BuiltInProfile.OTHER);
        // The names the Tier-1 and MariaDB drivers report.
        assertThat(VendorResolver.vendorOf("H2")).isEqualTo(DatabaseVendor.H2);
        assertThat(VendorResolver.vendorOf("PostgreSQL")).isEqualTo(DatabaseVendor.POSTGRESQL);
        assertThat(VendorResolver.vendorOf("MySQL")).isEqualTo(DatabaseVendor.MYSQL);
        assertThat(VendorResolver.vendorOf("MariaDB")).isEqualTo(DatabaseVendor.MARIADB);
    }

    @Test
    void r_prf_07_the_streaming_mode_picks_the_mysql_profile_and_no_other() {
        Map<DatabaseVendor, VendorProfile> none = Map.of();
        assertThat(VendorResolver.profileFor(DatabaseVendor.MYSQL, MysqlStreamingMode.ROW_BY_ROW, none))
                .isSameAs(BuiltInProfile.MYSQL);
        assertThat(VendorResolver.profileFor(DatabaseVendor.MYSQL, MysqlStreamingMode.CURSOR_FETCH, none))
                .isSameAs(BuiltInProfile.MYSQL_CURSOR_FETCH);
        assertThat(VendorResolver.profileFor(DatabaseVendor.H2, MysqlStreamingMode.CURSOR_FETCH, none))
                .isSameAs(BuiltInProfile.H2);
    }

    @Test
    void r_vnd_06_a_detected_vendor_without_a_profile_uses_the_other_one() {
        for (DatabaseVendor vendor : List.of(DatabaseVendor.MARIADB, DatabaseVendor.ORACLE, DatabaseVendor.SQLSERVER)) {
            assertThat(profileFor(vendor, Map.of())).as(vendor.name()).isSameAs(BuiltInProfile.OTHER);
        }
        var customOther = new CustomProfile(DatabaseVendor.OTHER);
        assertThat(profileFor(DatabaseVendor.ORACLE, Map.of(DatabaseVendor.OTHER, customOther)))
                .isSameAs(customOther);
    }

    @Test
    void r_vnd_03_a_discovered_profile_takes_precedence_over_the_built_in_one() {
        var custom = new CustomProfile(DatabaseVendor.POSTGRESQL);
        var discovered = VendorResolver.byVendor(List.of(custom));
        assertThat(profileFor(DatabaseVendor.POSTGRESQL, discovered)).isSameAs(custom);
        assertThat(profileFor(DatabaseVendor.H2, discovered)).isSameAs(BuiltInProfile.H2);
    }

    @Test
    void r_vnd_03_a_supplied_profile_takes_precedence_over_a_discovered_and_a_built_in_one() {
        var discovered = new CustomProfile(DatabaseVendor.POSTGRESQL);
        var supplied = new CustomProfile(DatabaseVendor.POSTGRESQL);
        var suppliedOther = new CustomProfile(DatabaseVendor.OTHER);
        ResolvedVendor postgres = resolved(DatabaseVendor.POSTGRESQL, Map.of(DatabaseVendor.POSTGRESQL, discovered));
        assertThat(postgres.profile()).isSameAs(discovered);
        ResolvedVendor withSupplied = VendorResolver.withSupplied(postgres, List.of(suppliedOther, supplied));
        assertThat(withSupplied.profile()).isSameAs(supplied);
        assertThat(withSupplied.detectedVendor()).isEqualTo(DatabaseVendor.POSTGRESQL);
        assertThat(withSupplied.source()).isEqualTo(ResolvedVendor.Source.METADATA);
        // A built-in profile serves H2, so a supplied OTHER one does not replace it; nothing supplied changes nothing.
        ResolvedVendor h2 = resolved(DatabaseVendor.H2, Map.of());
        assertThat(VendorResolver.withSupplied(h2, List.of(supplied, suppliedOther))).isSameAs(h2);
        assertThat(VendorResolver.withSupplied(postgres, List.of())).isSameAs(postgres);
    }

    @Test
    void r_vnd_06_a_supplied_other_profile_serves_a_vendor_that_fell_back_to_other() {
        var discoveredOther = new CustomProfile(DatabaseVendor.OTHER);
        var suppliedOther = new CustomProfile(DatabaseVendor.OTHER);
        var suppliedOracle = new CustomProfile(DatabaseVendor.ORACLE);
        ResolvedVendor oracle = resolved(DatabaseVendor.ORACLE, Map.of(DatabaseVendor.OTHER, discoveredOther));
        assertThat(oracle.profile()).isSameAs(discoveredOther);
        assertThat(VendorResolver.withSupplied(oracle, List.of(suppliedOther)).profile()).isSameAs(suppliedOther);
        // One supplied for the vendor itself wins over a supplied OTHER one, whatever their order.
        assertThat(VendorResolver.withSupplied(oracle, List.of(suppliedOther, suppliedOracle)).profile())
                .isSameAs(suppliedOracle);
    }

    @Test
    void r_vnd_03_two_discovered_profiles_for_one_vendor_throw_mq4002() {
        var first = new CustomProfile(DatabaseVendor.MYSQL);
        var second = new CustomProfile(DatabaseVendor.MYSQL);
        assertThatThrownBy(() -> VendorResolver.byVendor(List.of(first, second)))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4002))
                .hasMessageContaining("MYSQL");
    }

    @Test
    void r_prf_11_the_built_in_profiles_carry_the_tier1_values() {
        assertProfile(BuiltInProfile.H2, DatabaseVendor.H2, 10_000, 100_000, NullOrdering.NULLS_FIRST);
        assertProfile(BuiltInProfile.POSTGRESQL, DatabaseVendor.POSTGRESQL, 10_000, 65_535, NullOrdering.NULLS_LAST);
        assertProfile(BuiltInProfile.MYSQL, DatabaseVendor.MYSQL, 10_000, 65_535, NullOrdering.NULLS_FIRST);
        RenderOptions portable = RenderOptions.portable();
        assertProfile(BuiltInProfile.OTHER, DatabaseVendor.OTHER, portable.maxInListSize(),
                portable.maxBindParameters(), NullOrdering.UNKNOWN);
        assertThat(portable.nullPrecedenceRenderer()).isEmpty();
    }

    @Test
    void ac_vnd_07_only_h2_and_postgresql_write_reading_their_own_table_and_a_profile_that_does_not_say_never_does() {
        // The TCK's ProfileValuesTest checks the Tier-1 values against each database.
        assertThat(BuiltInProfile.H2.targetTableInSubquery()).isTrue();
        assertThat(BuiltInProfile.POSTGRESQL.targetTableInSubquery()).isTrue();
        assertThat(BuiltInProfile.MYSQL.targetTableInSubquery()).isFalse();
        assertThat(BuiltInProfile.MYSQL_CURSOR_FETCH.targetTableInSubquery()).isFalse();
        assertThat(BuiltInProfile.OTHER.targetTableInSubquery()).isFalse();
        assertThat(new CustomProfile(DatabaseVendor.H2).targetTableInSubquery()).isFalse();
    }

    private static void assertProfile(
            VendorProfile profile, DatabaseVendor vendor, int maxIn, int maxBinds, NullOrdering nullOrdering) {
        assertThat(profile.vendor()).isEqualTo(vendor);
        assertThat(profile.maxInListSize()).as(vendor + " IN list").isEqualTo(maxIn);
        assertThat(profile.maxBindParameters()).as(vendor + " binds").isEqualTo(maxBinds);
        assertThat(profile.defaultAscendingNullOrdering()).as(vendor + " NULLs").isEqualTo(nullOrdering);
    }

    @Test
    void r_prf_05_the_timeout_is_whole_seconds_rounded_up_and_never_zero() {
        assertThat(timeoutHint(Duration.ofNanos(1))).isEqualTo(1000);
        assertThat(timeoutHint(Duration.ofMillis(1))).isEqualTo(1000);
        assertThat(timeoutHint(Duration.ofSeconds(2))).isEqualTo(2000);
        assertThat(timeoutHint(Duration.ofMillis(2001))).isEqualTo(3000);
        assertThat(timeoutHint(Duration.ofSeconds(Long.MAX_VALUE, 1))).isEqualTo(Integer.MAX_VALUE / 1000 * 1000);
    }

    /** The value the built-in profiles set the timeout hint to for {@code timeout}. */
    private static Object timeoutHint(Duration timeout) {
        Object[] hint = new Object[1];
        Query query = (Query) java.lang.reflect.Proxy.newProxyInstance(VendorResolverTest.class.getClassLoader(),
                new Class<?>[] {Query.class}, (proxy, method, args) -> {
                    assertThat(method.getName()).isEqualTo("setHint");
                    assertThat(args[0]).isEqualTo(BuiltInProfile.TIMEOUT_HINT);
                    hint[0] = args[1];
                    return proxy;
                });
        BuiltInProfile.H2.applyTimeout(query, timeout);
        return hint[0];
    }

    /** {@code vendor} as resolved from {@code DatabaseMetaData} with {@code discovered} on the class path. */
    private static ResolvedVendor resolved(DatabaseVendor vendor, Map<DatabaseVendor, VendorProfile> discovered) {
        return new ResolvedVendor(profileFor(vendor, discovered), null, vendor, ResolvedVendor.Source.METADATA, "");
    }

    @Test
    void r_vnd_04_a_provider_support_that_does_not_load_is_skipped() {
        ProviderSupport working = new ProviderSupport() {
            @Override
            public boolean supports(jakarta.persistence.EntityManagerFactory emf) {
                return true;
            }
        };
        Iterable<ProviderSupport> discovered = () -> new Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
                return next < 3;
            }

            @Override
            public ProviderSupport next() {
                if (next++ == 0) {
                    throw new ServiceConfigurationError("provider library absent");
                }
                return working;
            }
        };

        assertThat(VendorResolver.loadable(discovered)).containsExactly(working, working);
    }

    @Test
    void r_vnd_04_an_iterator_that_always_throws_stops_after_the_skip_bound_and_keeps_what_loaded() {
        int[] calls = {0};
        Iterable<ProviderSupport> discovered = () -> new Iterator<>() {
            @Override
            public boolean hasNext() {
                calls[0]++;
                throw new ServiceConfigurationError("provider library absent");
            }

            @Override
            public ProviderSupport next() {
                throw new java.util.NoSuchElementException();
            }
        };

        assertThat(VendorResolver.loadable(discovered)).isEmpty();
        assertThat(calls[0]).isEqualTo(100);
    }

    private static VendorProfile profileFor(DatabaseVendor vendor, Map<DatabaseVendor, VendorProfile> discovered) {
        return VendorResolver.profileFor(vendor, MysqlStreamingMode.ROW_BY_ROW, discovered);
    }
}
