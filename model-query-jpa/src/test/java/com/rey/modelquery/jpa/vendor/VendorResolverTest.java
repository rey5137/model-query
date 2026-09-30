package com.rey.modelquery.jpa.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import jakarta.persistence.Query;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    void r_vnd_06_an_unknown_product_name_resolves_to_the_other_profile() {
        assertThat(VendorResolver.vendorOf("Apache Derby")).isEqualTo(DatabaseVendor.OTHER);
        assertThat(VendorResolver.vendorOf(null)).isEqualTo(DatabaseVendor.OTHER);
        assertThat(VendorResolver.profileFor(DatabaseVendor.OTHER, Map.of())).isSameAs(BuiltInProfile.OTHER);
        // The names the Tier-1 and MariaDB drivers report.
        assertThat(VendorResolver.vendorOf("H2")).isEqualTo(DatabaseVendor.H2);
        assertThat(VendorResolver.vendorOf("PostgreSQL")).isEqualTo(DatabaseVendor.POSTGRESQL);
        assertThat(VendorResolver.vendorOf("MySQL")).isEqualTo(DatabaseVendor.MYSQL);
        assertThat(VendorResolver.vendorOf("MariaDB")).isEqualTo(DatabaseVendor.MARIADB);
    }

    @Test
    void r_vnd_06_a_detected_vendor_without_a_profile_uses_the_other_one() {
        for (DatabaseVendor vendor : List.of(DatabaseVendor.MARIADB, DatabaseVendor.ORACLE, DatabaseVendor.SQLSERVER)) {
            assertThat(VendorResolver.profileFor(vendor, Map.of())).as(vendor.name()).isSameAs(BuiltInProfile.OTHER);
        }
        var customOther = new CustomProfile(DatabaseVendor.OTHER);
        assertThat(VendorResolver.profileFor(DatabaseVendor.ORACLE, Map.of(DatabaseVendor.OTHER, customOther)))
                .isSameAs(customOther);
    }

    @Test
    void r_vnd_03_a_discovered_profile_takes_precedence_over_the_built_in_one() {
        var custom = new CustomProfile(DatabaseVendor.POSTGRESQL);
        var discovered = VendorResolver.byVendor(List.of(custom));
        assertThat(VendorResolver.profileFor(DatabaseVendor.POSTGRESQL, discovered)).isSameAs(custom);
        assertThat(VendorResolver.profileFor(DatabaseVendor.H2, discovered)).isSameAs(BuiltInProfile.H2);
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
                portable.maxBindParameters(), portable.defaultAscendingNullOrdering());
        assertThat(portable.nullPrecedenceRenderer()).isEmpty();
        assertThat(BuiltInProfile.values()).noneMatch(VendorProfile::targetTableInSubquery);
    }

    private static void assertProfile(
            VendorProfile profile, DatabaseVendor vendor, int maxIn, int maxBinds, NullOrdering nullOrdering) {
        assertThat(profile.vendor()).isEqualTo(vendor);
        assertThat(profile.maxInListSize()).as(vendor + " IN list").isEqualTo(maxIn);
        assertThat(profile.maxBindParameters()).as(vendor + " binds").isEqualTo(maxBinds);
        assertThat(profile.defaultAscendingNullOrdering()).as(vendor + " NULLs").isEqualTo(nullOrdering);
    }
}
