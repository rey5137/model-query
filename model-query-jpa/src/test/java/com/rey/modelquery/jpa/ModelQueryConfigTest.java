package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import java.time.Duration;
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
}
