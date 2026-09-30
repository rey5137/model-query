package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import jakarta.persistence.Query;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ModelQueryConfigTest {

    @Test
    void r_pag_07_the_step_two_batch_size_is_unset_by_default_and_kept_by_the_other_setters() {
        assertThat(ModelQueryConfig.defaults().primaryKeyFirstBatchSize()).isEmpty();
        var config = ModelQueryConfig.defaults().primaryKeyFirstBatchSize(250).vendor(DatabaseVendor.H2);
        assertThat(config.primaryKeyFirstBatchSize()).isEqualTo(OptionalInt.of(250));
        assertThat(config.vendor()).contains(DatabaseVendor.H2);
        assertThat(config.primaryKeyFirstBatchSize(1).vendor()).contains(DatabaseVendor.H2);
    }

    @Test
    void r_pag_07_a_step_two_batch_size_below_one_throws_mq4003() {
        for (int batchSize : new int[] {0, -1}) {
            assertThatThrownBy(() -> ModelQueryConfig.defaults().primaryKeyFirstBatchSize(batchSize))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4003))
                    .hasMessage(MqCode.MQ4003.code() + ": primaryKeyFirstBatchSize " + batchSize + " is below one");
        }
    }

    @Test
    void r_exe_11_the_query_timeout_is_unset_by_default_and_kept_by_the_other_setters() {
        assertThat(ModelQueryConfig.defaults().queryTimeout()).isEmpty();
        var config = ModelQueryConfig.defaults().queryTimeout(Duration.ofSeconds(3));
        assertThat(config.queryTimeout()).contains(Duration.ofSeconds(3));
        var others = config.vendor(DatabaseVendor.H2).primaryKeyFirstBatchSize(5)
                .mysqlStreamingMode(MysqlStreamingMode.CURSOR_FETCH);
        assertThat(others.queryTimeout()).contains(Duration.ofSeconds(3));
        assertThat(others.vendor()).contains(DatabaseVendor.H2);
        assertThat(others.primaryKeyFirstBatchSize()).isEqualTo(OptionalInt.of(5));
        assertThat(others.mysqlStreamingMode()).isEqualTo(MysqlStreamingMode.CURSOR_FETCH);
        assertThat(others.queryTimeout(Duration.ofMillis(1)).mysqlStreamingMode())
                .isEqualTo(MysqlStreamingMode.CURSOR_FETCH);
    }

    @Test
    void r_exe_11_a_query_timeout_that_is_not_positive_throws_mq4003() {
        for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofMillis(-1)}) {
            assertThatThrownBy(() -> ModelQueryConfig.defaults().queryTimeout(timeout))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4003))
                    .hasMessage(MqCode.MQ4003.code() + ": queryTimeout " + timeout + " is not positive");
        }
    }

    @Test
    void r_prf_07_mysql_streams_row_by_row_unless_set_and_the_setter_keeps_the_other_settings() {
        assertThat(ModelQueryConfig.defaults().mysqlStreamingMode()).isEqualTo(MysqlStreamingMode.ROW_BY_ROW);
        var config = ModelQueryConfig.defaults().vendor(DatabaseVendor.MYSQL).queryTimeout(Duration.ofSeconds(1))
                .primaryKeyFirstBatchSize(7).mysqlStreamingMode(MysqlStreamingMode.CURSOR_FETCH);
        assertThat(config.mysqlStreamingMode()).isEqualTo(MysqlStreamingMode.CURSOR_FETCH);
        assertThat(config.vendor()).contains(DatabaseVendor.MYSQL);
        assertThat(config.queryTimeout()).contains(Duration.ofSeconds(1));
        assertThat(config.primaryKeyFirstBatchSize()).isEqualTo(OptionalInt.of(7));
    }

    @Test
    void r_pag_05_keyset_null_keys_fail_unless_set_and_the_setter_keeps_the_other_settings() {
        assertThat(ModelQueryConfig.defaults().keysetNullKeys()).isEqualTo(KeysetNullKeys.FAIL);
        var config = ModelQueryConfig.defaults().vendor(DatabaseVendor.MYSQL).queryTimeout(Duration.ofSeconds(1))
                .primaryKeyFirstBatchSize(7).mysqlStreamingMode(MysqlStreamingMode.CURSOR_FETCH)
                .keysetNullKeys(KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThat(config.keysetNullKeys()).isEqualTo(KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThat(config.vendor()).contains(DatabaseVendor.MYSQL);
        assertThat(config.queryTimeout()).contains(Duration.ofSeconds(1));
        assertThat(config.primaryKeyFirstBatchSize()).isEqualTo(OptionalInt.of(7));
        assertThat(config.mysqlStreamingMode()).isEqualTo(MysqlStreamingMode.CURSOR_FETCH);
        assertThat(config.vendor(DatabaseVendor.H2).primaryKeyFirstBatchSize(3).queryTimeout(Duration.ofSeconds(2))
                .mysqlStreamingMode(MysqlStreamingMode.ROW_BY_ROW).keysetNullKeys())
                .isEqualTo(KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
    }

    @Test
    void ac_spr_07_every_spring_property_is_settable_on_the_plain_config() {
        // Every §3 property but the Future (M8) bulk-write ones, in the table's order (R-SPR-08).
        var config = ModelQueryConfig.defaults()
                .vendor(DatabaseVendor.POSTGRESQL)
                .exportPageSize(2_000)
                .primaryKeyFirstBatchSize(300)
                .streamFetchSize(50)
                .mysqlStreamingMode(MysqlStreamingMode.CURSOR_FETCH)
                .queryTimeout(Duration.ofSeconds(9))
                .keysetNullKeys(KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThat(config.vendor()).contains(DatabaseVendor.POSTGRESQL);
        assertThat(config.exportPageSize()).isEqualTo(2_000);
        assertThat(config.primaryKeyFirstBatchSize()).isEqualTo(OptionalInt.of(300));
        assertThat(config.streamFetchSize()).isEqualTo(50);
        assertThat(config.mysqlStreamingMode()).isEqualTo(MysqlStreamingMode.CURSOR_FETCH);
        assertThat(config.queryTimeout()).contains(Duration.ofSeconds(9));
        assertThat(config.keysetNullKeys()).isEqualTo(KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
    }

    @Test
    void r_qry_15_the_export_page_size_and_stream_fetch_size_default_and_are_kept_by_the_other_setters() {
        assertThat(ModelQueryConfig.defaults().exportPageSize()).isEqualTo(1_000);
        assertThat(ModelQueryConfig.defaults().streamFetchSize()).isEqualTo(500);
        var supplied = List.<VendorProfile>of(new StubProfile(DatabaseVendor.H2));
        var config = ModelQueryConfig.defaults().exportPageSize(1).streamFetchSize(2).vendorProfiles(supplied);
        var others = config.vendor(DatabaseVendor.MYSQL).primaryKeyFirstBatchSize(3)
                .queryTimeout(Duration.ofSeconds(4)).mysqlStreamingMode(MysqlStreamingMode.CURSOR_FETCH)
                .keysetNullKeys(KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThat(others.exportPageSize()).isEqualTo(1);
        assertThat(others.streamFetchSize()).isEqualTo(2);
        assertThat(others.vendorProfiles()).isEqualTo(supplied);
        var sized = others.exportPageSize(5).streamFetchSize(6).vendorProfiles(List.of());
        assertThat(sized.vendor()).contains(DatabaseVendor.MYSQL);
        assertThat(sized.primaryKeyFirstBatchSize()).isEqualTo(OptionalInt.of(3));
        assertThat(sized.queryTimeout()).contains(Duration.ofSeconds(4));
        assertThat(sized.mysqlStreamingMode()).isEqualTo(MysqlStreamingMode.CURSOR_FETCH);
        assertThat(sized.keysetNullKeys()).isEqualTo(KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThat(sized.exportPageSize()).isEqualTo(5);
        assertThat(sized.streamFetchSize()).isEqualTo(6);
        assertThat(sized.vendorProfiles()).isEmpty();
    }

    @Test
    void r_qry_15_an_export_page_size_or_stream_fetch_size_below_one_throws_mq4003() {
        for (int size : new int[] {0, -1}) {
            assertThatThrownBy(() -> ModelQueryConfig.defaults().exportPageSize(size))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4003))
                    .hasMessage(MqCode.MQ4003.code() + ": exportPageSize " + size + " is below one");
            assertThatThrownBy(() -> ModelQueryConfig.defaults().streamFetchSize(size))
                    .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4003))
                    .hasMessage(MqCode.MQ4003.code() + ": streamFetchSize " + size + " is below one");
        }
    }

    @Test
    void r_vnd_03_no_profile_is_supplied_by_default_and_two_supplied_for_one_vendor_throw_mq4002() {
        assertThat(ModelQueryConfig.defaults().vendorProfiles()).isEmpty();
        var h2 = new StubProfile(DatabaseVendor.H2);
        var other = new StubProfile(DatabaseVendor.OTHER);
        assertThat(ModelQueryConfig.defaults().vendorProfiles(List.of(h2, other)).vendorProfiles())
                .containsExactly(h2, other);
        assertThatThrownBy(() -> ModelQueryConfig.defaults()
                .vendorProfiles(List.of(h2, other, new StubProfile(DatabaseVendor.H2))))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4002))
                .hasMessageContaining("VendorProfile for H2")
                .hasMessageContaining("ModelQueryConfig.vendorProfiles(...)");
    }

    /** A supplied profile, as a plain-JPA caller or the Spring starter passes one. */
    private record StubProfile(DatabaseVendor vendor) implements VendorProfile {

        @Override
        public int maxInListSize() {
            return 100;
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
}
