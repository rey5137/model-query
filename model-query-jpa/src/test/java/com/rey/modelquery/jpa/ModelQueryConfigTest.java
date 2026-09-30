package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
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
}
