package com.rey.modelquery.portability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.internal.TargetFacts;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class TargetTest {

    @Test
    void ac_ins_10_a_full_version_sets_product_version_major_and_minor() {
        Target target = Target.of(DatabaseVendor.H2, "2.3.232");
        assertThat(TargetFacts.productName(target)).isEqualTo("H2");
        assertThat(target.version()).contains("2.3.232");
        assertThat(TargetFacts.majorVersion(target)).contains(2);
        assertThat(TargetFacts.minorVersion(target)).contains(3);
        assertThat(TargetFacts.minorVersion(Target.of(DatabaseVendor.POSTGRESQL, "15"))).contains(0);
        assertThat(TargetFacts.majorVersion(Target.of(DatabaseVendor.MARIADB, "10.11.6-MariaDB"))).contains(10);
        assertThat(target).isEqualTo(Target.of(DatabaseVendor.H2, "2.3.232"))
                .isNotEqualTo(Target.of(DatabaseVendor.H2, "2.3.230"))
                .isNotEqualTo(Target.of(DatabaseVendor.H2));
    }

    @Test
    void ac_ins_10_no_version_leaves_the_dialect_minimum() {
        Target target = Target.of(DatabaseVendor.MYSQL);
        assertThat(TargetFacts.productName(target)).isEqualTo("MySQL");
        assertThat(target.version()).isEmpty();
        assertThat(TargetFacts.majorVersion(target)).isEmpty();
        assertThat(TargetFacts.minorVersion(target)).isEmpty();
        assertThat(target).hasToString("MYSQL (dialect minimum)");
    }

    @Test
    void ac_ins_10_a_vendor_with_no_product_name_or_a_bad_version_is_mq4013() {
        for (DatabaseVendor vendor : new DatabaseVendor[] {DatabaseVendor.OTHER, DatabaseVendor.ORACLE}) {
            assertMq4013(() -> Target.of(vendor), vendor.name());
        }
        assertMq4013(() -> Target.of(DatabaseVendor.POSTGRESQL, "latest"), "'latest'");
    }

    private static void assertMq4013(ThrowingCallable call, String detail) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4013))
                .hasMessageContaining(detail);
    }
}
