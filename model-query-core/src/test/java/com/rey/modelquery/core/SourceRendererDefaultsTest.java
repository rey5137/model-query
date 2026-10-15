package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

/** A {@link SourceRenderer} that implements only {@code cte} refuses the rest with {@code MQ4015} (R-SRC-12). */
class SourceRendererDefaultsTest {

    private static final SourceRenderer CTE_ONLY = new SourceRenderer() {
        @Override
        public Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body) {
            return null;
        }
    };

    @Test
    void r_src_12_window_combine_and_recursive_default_to_mq4015() {
        assertMq4015(() -> CTE_ONLY.window(null, null));
        assertMq4015(() -> CTE_ONLY.combine(null, SetOperation.UNION, null, null));
        assertMq4015(() -> CTE_ONLY.recursive(null, null));
    }

    private static void assertMq4015(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ModelQueryConfigurationException.class);
        assertThat(((ModelQueryConfigurationException) thrown).code()).isEqualTo(MqCode.MQ4015);
    }
}
