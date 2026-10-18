package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

/** A {@link SourceRenderer} that implements only {@code cte} refuses the rest with {@code MQ4014} (R-SRC-12). */
class SourceRendererDefaultsTest {

    private static final SourceRenderer CTE_ONLY = new SourceRenderer() {
        @Override
        public Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body) {
            return null;
        }
    };

    @Test
    void ac_src_16_window_combine_and_recursive_default_to_mq4014() {
        assertMq4014(() -> CTE_ONLY.window(null, null), "window functions");
        assertMq4014(() -> CTE_ONLY.combine(null, SetOperation.UNION, null, null), "set operations");
        assertMq4014(() -> CTE_ONLY.recursive(null, null), "recursive CTEs");
        assertMq4014(() -> CTE_ONLY.register(null, null), "chained CTEs");
        assertMq4014(() -> CTE_ONLY.registerRecursive(null, null), "chained CTEs");
        assertMq4014(() -> CTE_ONLY.from(null, null), "chained CTEs");
    }

    private static void assertMq4014(Runnable call, String feature) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ModelQueryConfigurationException.class)
                .hasMessageContaining(feature);
        assertThat(((ModelQueryConfigurationException) thrown).code()).isEqualTo(MqCode.MQ4014);
    }
}
